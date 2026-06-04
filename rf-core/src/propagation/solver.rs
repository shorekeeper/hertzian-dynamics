//! PropagationSolver: compose every physical effect into one query.
//!
//! Construction holds borrowed references to the voxel grid, the
//! material table and the ionospheric LUT, so the same solver can
//! serve many (Tx, Rx, frequency) requests in a tight loop without
//! re-fetching shared resources. Per-call state (the Rayleigh fader)
//! is passed in by the caller, so the solver itself is `Send + Sync`
//! once the borrowed objects are.
//!
//! Obstruction model
//!
//! Obstruction loss is computed by the Fresnel aperture model in
//! `fresnel_aperture_loss_db`. It samples the first Fresnel zone with a
//! bundle of rays and weights their transmission toward the centre of
//! the zone, so lateral and near line obstacles count, the loss grades
//! smoothly as an obstacle nears the line, and the signal diffracts
//! around a small block instead of being hard blocked. The standalone
//! knife edge functions in `propagation::knife_edge` remain for callers
//! that work from a one dimensional terrain profile.
//!
//! Why the rays converge at the endpoints
//!
//! The first Fresnel zone is an ellipsoid with foci at the transmitter
//! and the receiver: it pinches to a point at each end and bulges to its
//! widest at the midpoint. An earlier version of the aperture model cast
//! rays parallel to the line of sight, a cylinder rather than an
//! ellipsoid. That cylinder had a fatal flaw at the endpoints: a small
//! obstruction wrapped tightly around the transmitter, such as a metal
//! box sealing it in, only intersected the one central ray while the
//! rest of the wide bundle flew past it metres to the side, so a sealed
//! transmitter still delivered a near perfect signal. The rays now bend:
//! each is a two segment polyline from the true transmitter point, out
//! to a point offset from the midpoint, and back to the true receiver
//! point. Every ray therefore passes through whatever sits at the
//! transmitter and at the receiver, so sealing the transmitter in iron
//! blocks the entire bundle and closes the path, while the bundle still
//! fans out at the midpoint to catch obstacles beside the line. The two
//! segments together traverse the same total length as the old single
//! ray, so this corrects the geometry at no extra cost.
//!
//! Earth curvature
//!
//! On top of voxel obstruction the solver adds the radio horizon. The
//! flat world would otherwise let a ground wave run forever; the
//! curvature term from `propagation::curvature` models the Earth bulge
//! rising into the path past the horizon and folds the result into the
//! ground wave candidate only. Skywave is left untouched because it
//! reaches over the horizon through the ionosphere, which is exactly why
//! a HF skywave path outruns the ground wave at long range. The term is
//! reported in the breakdown's `diffraction_db` field, which the
//! aperture rework had freed up.

use core::f32::consts::PI;

use crate::propagation::curvature;
use crate::propagation::doppler::{doppler_shift_hz, radial_approach_velocity};
use crate::propagation::fading::RayleighFading;
use crate::propagation::friis::{free_space_loss_db, wavelength_m, SPEED_OF_LIGHT_M_S};
use crate::propagation::ionosphere::IonosphereLut;
use crate::propagation::material::{MaterialId, MaterialTable};
use crate::propagation::voxel::{raycast_absorption_db, VoxelGrid};
use crate::types::propagation::{LossBreakdown, PathLoss};

/// Tunable thresholds for the solver. Defaults match the engine's
/// design targets; the JNI layer lets Java override the curvature set.
#[derive(Copy, Clone, Debug)]
pub struct SolverConfig {
    /// Maximum absorption to accumulate along the ray before the solver
    /// gives up and reports a closed path.
    pub absorption_budget_db: f32,
    /// Sampling step, in metres, for the one dimensional terrain profile
    /// consumed by the standalone knife edge functions. The solver does
    /// not use it; kept for hand built profiles.
    pub terrain_step_m: f32,
    /// Maximum length of a hand built terrain profile buffer. Not used by
    /// the solver; kept for the standalone knife edge path.
    pub max_profile_samples: usize,
    /// When the line of sight loss exceeds this value, attempt skywave.
    pub skywave_attempt_threshold_db: f32,
    /// HF band lower edge for the skywave attempt.
    pub skywave_lower_band_hz: f64,
    /// HF band upper edge for the skywave attempt.
    pub skywave_upper_band_hz: f64,
    /// Apply the Earth curvature radio horizon to ground waves. On by
    /// default; a server can turn it off for a pure free space world.
    pub model_curvature: bool,
    /// Earth radius for the curvature model, in metres. Lowering it pulls
    /// the radio horizon in and steepens the beyond horizon fall, the
    /// single knob for a more compact world without changing the physics.
    pub earth_radius_m: f32,
    /// Effective Earth radius k factor; the standard atmospheric value is
    /// 4/3.
    pub earth_k_factor: f32,
    /// World height counted as the ground reference for antenna height.
    /// Height above this drives the radio horizon, so a radio on a tower
    /// reaches further than one on the ground. Defaults to sea level 63.
    pub curvature_ground_ref_m: f32,
    /// Floor on antenna height above the reference, in metres, so a radio
    /// at or below the reference still has a small non zero horizon.
    pub curvature_min_height_m: f32,
}

impl Default for SolverConfig {
    fn default() -> Self {
        Self {
            absorption_budget_db: 300.0,
            terrain_step_m: 8.0,
            max_profile_samples: 512,
            skywave_attempt_threshold_db: 100.0,
            skywave_lower_band_hz: 3.0e6,
            skywave_upper_band_hz: 30.0e6,
            model_curvature: true,
            earth_radius_m: curvature::MEAN_EARTH_RADIUS_M,
            earth_k_factor: curvature::STANDARD_K_FACTOR,
            curvature_ground_ref_m: 63.0,
            curvature_min_height_m: 1.0,
        }
    }
}

/// Per query inputs.
#[derive(Copy, Clone, Debug)]
pub struct PropagationInputs {
    pub tx_pos: [f32; 3],
    pub rx_pos: [f32; 3],
    pub tx_vel: [f32; 3],
    pub rx_vel: [f32; 3],
    pub frequency_hz: f64,
    pub tx_gain: f32,
    pub rx_gain: f32,
    pub local_hour: f32,
}

/// Stateless facade that bundles the shared resources together.
pub struct PropagationSolver<'a, G: VoxelGrid> {
    grid: &'a G,
    materials: &'a MaterialTable,
    ionosphere: &'a IonosphereLut,
    config: SolverConfig,
}

impl<'a, G: VoxelGrid> PropagationSolver<'a, G> {
    pub fn new(
        grid: &'a G,
        materials: &'a MaterialTable,
        ionosphere: &'a IonosphereLut,
        config: SolverConfig,
    ) -> Self {
        Self { grid, materials, ionosphere, config }
    }

    pub fn solve(
        &self,
        inputs: PropagationInputs,
        fading: &mut RayleighFading,
    ) -> PathLoss {
        let dx = inputs.rx_pos[0] - inputs.tx_pos[0];
        let dy = inputs.rx_pos[1] - inputs.tx_pos[1];
        let dz = inputs.rx_pos[2] - inputs.tx_pos[2];
        let dist_m = (dx * dx + dy * dy + dz * dz).sqrt();
        if dist_m <= 0.0 {
            return PathLoss::UNIT;
        }

        let free_db = free_space_loss_db(dist_m, inputs.frequency_hz);

        let aperture_db = fresnel_aperture_loss_db(
            self.grid,
            self.materials,
            inputs.tx_pos,
            inputs.rx_pos,
            inputs.frequency_hz,
            self.config.absorption_budget_db,
        );

        let curvature_db = if self.config.model_curvature {
            let ground_d = (dx * dx + dz * dz).sqrt();
            // Antenna height is measured above the local terrain surface
            // under each endpoint, not above a single global reference
            // plane. The global plane treated a radio standing on a hill
            // at world y = 110 as a 47 metre mast, which gave it a vastly
            // inflated radio horizon. Scanning the voxel grid down from the
            // endpoint to the first solid voxel recovers the real ground
            // under it, so a set sitting on the ground is one metre high
            // wherever the ground happens to be. The endpoint chunks are
            // loaded, a player or a force loaded broadcaster is there, so
            // the scan finds ground; if it does not, it falls back to the
            // configured global reference.
            let tx_ground = self
                .local_ground_m(inputs.tx_pos)
                .unwrap_or(self.config.curvature_ground_ref_m);
            let rx_ground = self
                .local_ground_m(inputs.rx_pos)
                .unwrap_or(self.config.curvature_ground_ref_m);
            let h1 = (inputs.tx_pos[1] - tx_ground)
                .max(self.config.curvature_min_height_m);
            let h2 = (inputs.rx_pos[1] - rx_ground)
                .max(self.config.curvature_min_height_m);
            curvature::curvature_loss_db(
                h1, h2, ground_d, inputs.frequency_hz,
                self.config.earth_radius_m, self.config.earth_k_factor,
            )
        } else {
            0.0
        };

        let ground_wave_db = free_db + aperture_db + curvature_db;

        let try_skywave = inputs.frequency_hz >= self.config.skywave_lower_band_hz
            && inputs.frequency_hz <= self.config.skywave_upper_band_hz
            && ground_wave_db >= self.config.skywave_attempt_threshold_db;

        let (chosen_path_db, ion_db, virtual_dist_m) = if try_skywave {
            let ion = self.ionosphere.evaluate(
                inputs.frequency_hz, dist_m, inputs.local_hour);
            if ion.absorption_db.is_finite() {
                let virt = self.ionosphere.virtual_path_length_m(dist_m, ion.hops);
                let sky_free_db = free_space_loss_db(virt, inputs.frequency_hz);
                let sky_total = sky_free_db + ion.absorption_db;
                if sky_total < ground_wave_db {
                    (sky_total, ion.absorption_db, Some(virt))
                } else {
                    (ground_wave_db, 0.0, None)
                }
            } else {
                (ground_wave_db, f32::INFINITY, None)
            }
        } else {
            (ground_wave_db, 0.0, None)
        };

        let gain_db = 10.0 * (inputs.tx_gain.max(1e-9).log10() + inputs.rx_gain.max(1e-9).log10());
        let fade_db = fading.sample_db();

        let total_db = chosen_path_db - gain_db - fade_db;
        let linear = if total_db.is_finite() {
            10.0_f32.powf(-total_db / 10.0)
        } else {
            0.0
        };

        let delay_m = virtual_dist_m.unwrap_or(dist_m);
        let delay_seconds = delay_m / SPEED_OF_LIGHT_M_S;

        let v_radial = radial_approach_velocity(
            inputs.tx_pos, inputs.rx_pos, inputs.tx_vel, inputs.rx_vel);
        let doppler_hz = doppler_shift_hz(v_radial, inputs.frequency_hz);

        PathLoss {
            linear,
            db: total_db,
            components: LossBreakdown {
                free_space_db: if virtual_dist_m.is_some() {
                    free_space_loss_db(virtual_dist_m.unwrap(), inputs.frequency_hz)
                } else {
                    free_db
                },
                absorption_db: aperture_db,
                diffraction_db: if virtual_dist_m.is_some() { 0.0 } else { curvature_db },
                ionospheric_db: ion_db,
                fading_db: -fade_db,
            },
            doppler_hz,
            delay_seconds,
        }
    }

    /// Height in metres of the terrain surface directly under a world
    /// position, found by scanning the voxel grid downward from the
    /// position to the first non air voxel. Returns the top face of that
    /// voxel. None when no solid voxel is found within the scan window,
    /// which happens when the endpoint's chunk is not in the grid; the
    /// caller then falls back to the global ground reference.
    ///
    /// The scan window is bounded so the cost stays a fixed small number
    /// of grid lookups per endpoint. A window of 96 voxels covers the
    /// surface under any reasonable build while keeping the per solve cost
    /// negligible against the mix itself. The radio blocks themselves are
    /// air in the grid (they are not in the material map), so the scan
    /// passes through the set and finds the real ground beneath it.
    fn local_ground_m(&self, pos: [f32; 3]) -> Option<f32> {
        const SCAN_VOXELS: i32 = 96;
        let vs = self.grid.voxel_size_m().max(1e-3);
        let vx = (pos[0] / vs).floor() as i32;
        let vz = (pos[2] / vs).floor() as i32;
        let start_y = (pos[1] / vs).floor() as i32;
        for dy in 1..=SCAN_VOXELS {
            let y = start_y - dy;
            if self.grid.material_at(vx, y, vz) != MaterialId::AIR {
                return Some((y as f32 + 1.0) * vs);
            }
        }
        None
    }
}

/// Number of sample rays the Fresnel aperture model casts across the
/// first Fresnel zone at its widest setting.
const MAX_APERTURE_SAMPLES: usize = 24;

/// Loss in decibels from obstruction of the first Fresnel zone between
/// tx and rx. The rays are bent so they converge to the true endpoints;
/// see the module docs for why a parallel bundle let a sealed
/// transmitter still transmit.
fn fresnel_aperture_loss_db<G: VoxelGrid>(
    grid: &G,
    materials: &MaterialTable,
    tx: [f32; 3],
    rx: [f32; 3],
    frequency_hz: f64,
    budget_db: f32,
) -> f32 {
    let dx = rx[0] - tx[0];
    let dy = rx[1] - tx[1];
    let dz = rx[2] - tx[2];
    let dist = (dx * dx + dy * dy + dz * dz).sqrt();
    if !(dist > 0.0) {
        return 0.0;
    }
    let inv = 1.0 / dist;
    let dir = [dx * inv, dy * inv, dz * inv];

    let lambda = wavelength_m(frequency_hz) as f32;
    let r1 = 0.5 * (lambda * dist).sqrt();

    let voxel = grid.voxel_size_m().max(1e-3);
    let radial = (r1 / voxel).max(1.0);
    let n_samples = ((PI * radial * radial).ceil() as usize).clamp(1, MAX_APERTURE_SAMPLES);

    let (u, v) = perpendicular_basis(dir);
    let golden_angle = PI * (3.0 - 5.0_f32.sqrt());

    // Midpoint of the line of sight; the rays bend out from here.
    let mid = [
        (tx[0] + rx[0]) * 0.5,
        (tx[1] + rx[1]) * 0.5,
        (tx[2] + rx[2]) * 0.5,
    ];

    let mut sum_weight = 0.0_f32;
    let mut sum_weighted_transmission = 0.0_f32;

    for k in 0..n_samples {
        let rho = if k == 0 {
            0.0
        } else {
            (k as f32 / n_samples as f32).sqrt()
        };
        let radius = rho * r1;
        let theta = k as f32 * golden_angle;
        let (s, c) = theta.sin_cos();
        let off = [
            u[0] * (radius * c) + v[0] * (radius * s),
            u[1] * (radius * c) + v[1] * (radius * s),
            u[2] * (radius * c) + v[2] * (radius * s),
        ];
        // Bend vertex offset from the midpoint. The ray runs from the
        // true tx point to this vertex and on to the true rx point, so
        // it pinches at both endpoints and bulges in the middle.
        let bend = [mid[0] + off[0], mid[1] + off[1], mid[2] + off[2]];

        let abs1 = raycast_absorption_db(grid, materials, tx, bend, frequency_hz, budget_db);
        let abs2 = raycast_absorption_db(grid, materials, bend, rx, frequency_hz, budget_db);
        let abs_db = (abs1 + abs2).min(budget_db);

        let transmission = 10.0_f32.powf(-abs_db / 20.0);
        let weight = (0.5 * PI * rho * rho).cos().max(0.0);

        sum_weight += weight;
        sum_weighted_transmission += weight * transmission;
    }

    let factor = if sum_weight > 0.0 {
        sum_weighted_transmission / sum_weight
    } else {
        1.0
    };

    let floor = 10.0_f32.powf(-budget_db / 20.0);
    let loss = -20.0 * factor.max(floor).log10();
    loss.min(budget_db)
}

/// Two orthonormal vectors spanning the plane perpendicular to `dir`.
fn perpendicular_basis(dir: [f32; 3]) -> ([f32; 3], [f32; 3]) {
    let seed = if dir[1].abs() < 0.9 {
        [0.0, 1.0, 0.0]
    } else {
        [1.0, 0.0, 0.0]
    };
    let ux = dir[1] * seed[2] - dir[2] * seed[1];
    let uy = dir[2] * seed[0] - dir[0] * seed[2];
    let uz = dir[0] * seed[1] - dir[1] * seed[0];
    let ulen = (ux * ux + uy * uy + uz * uz).sqrt().max(1e-6);
    let u = [ux / ulen, uy / ulen, uz / ulen];
    let vx = dir[1] * u[2] - dir[2] * u[1];
    let vy = dir[2] * u[0] - dir[0] * u[2];
    let vz = dir[0] * u[1] - dir[1] * u[0];
    (u, [vx, vy, vz])
}