//! Coherent multipath: a deterministic image-method channel over the
//! axis-aligned voxel surfaces.
//!
//! Beyond the direct ray and the single ground bounce of the two-ray
//! model, a real link collects energy that has reflected off the walls,
//! floor and ceiling around the path. The reflected components add as
//! complex phasors, so the channel is a sum of resolvable paths rather
//! than a single ray. This module computes that sum geometrically, which
//! captures three effects the simpler models miss: deterministic
//! interference at the carrier, waveguiding down a corridor or tunnel
//! where reflections funnel energy that would otherwise spread, and the
//! reverberant build up inside an enclosed reflective space.
//!
//! Channel impulse response
//! ------------------------
//!
//! A multipath channel is described by its impulse response, a sum of
//! delta functions, one per resolvable path,
//!
//!     h(tau) = sum_i a_i delta(tau - tau_i),
//!
//! with complex amplitude a_i and delay tau_i = L_i / c for a path of
//! unfolded length L_i. The frequency response is the Fourier transform,
//!
//!     H(f) = sum_i a_i exp(-j 2 pi f tau_i).
//!
//! Narrowband evaluation
//! ---------------------
//!
//! The engine mixes one carrier at a time over a voice or broadcast
//! channel a few tens of kilohertz wide. The excess delays here are at
//! most a few hundred nanoseconds, so the coherence bandwidth
//! 1 / (2 pi tau_spread) is in the megahertz, far wider than any single
//! channel. The channel is therefore flat across one channel and the mixer
//! needs only the complex gain at the carrier,
//!
//!     H(f_c) = sum_i a_i exp(-j 2 pi f_c tau_i),
//!
//! returned here as a single power level in decibels. Frequency
//! selectivity is not lost: it appears across the spectrum, because the
//! solver is called once per carrier and a different carrier sees a
//! different H(f_c). A spectrum analyzer with a span far narrower than the
//! comb period sees a flat trace within one span; the audible multipath
//! effect is the overall level changing with position and environment, the
//! spatial fading a receiver feels as it moves through a reflective space.
//! The RMS delay spread is reported as a diagnostic and for any future
//! wideband channel; it does not drive the narrowband mixer.
//!
//! Path amplitude, referenced to the direct ray
//! --------------------------------------------
//!
//! Amplitudes are taken relative to the direct free space field so a clear
//! line of sight with no other path gives H = 1, a level of 0 dB relative
//! to free space. A path that reflects n times has
//!
//!     a_i = (d_los / L_i) * (prod_k Gamma_k) * t_i
//!           * exp( j ( sum_k phase(Gamma_k) - k (L_i - d_los) ) )
//!
//! where d_los is the straight line distance, Gamma_k is the complex
//! Fresnel reflection coefficient at bounce k (from
//! propagation::reflection, using the wall material permittivity), t_i is
//! the amplitude transmission through any obstruction along the folded
//! path, and k = 2 pi / lambda. The factor d_los / L_i is the excess
//! spreading loss of the longer reflected path. The direct ray is
//! a_0 = t_direct (the obstruction transmission of the line of sight), so
//! a clear line of sight gives a_0 = 1 and the two-ray ground reflection
//! is exactly the n = 1 term for the ground plane: this model contains the
//! two-ray result as a special case.
//!
//! Obstruction of reflected paths
//! ------------------------------
//!
//! Every reflected path is a polyline in real space: a straight bounce
//! from the transmitter to the first reflection point, then to the next,
//! and so on to the receiver. Each straight segment is raycast through the
//! grid for absorption, so a wall standing across a corridor blocks the
//! reflected paths that would pass through it, and a receiver sealed inside
//! solid material receives nothing because every segment reaching it
//! crosses the seal. This is what lets a block actually block: the line of
//! sight is removed by its own obstruction (the t_direct factor), and the
//! reflections that might route around it survive only if their geometry
//! is genuinely clear. The reflection points sit on the air side of each
//! wall face and are nudged a fraction of a voxel into the interior, so a
//! segment grazing along a corridor wall does not register the wall itself
//! as an obstruction; only material crossing the open volume counts.
//!
//! Level, null floor and gain ceiling
//! ----------------------------------
//!
//! The coherent sum can be smaller than the strongest single path (a
//! destructive interference null) or larger (constructive addition or
//! waveguiding). The level is taken from the coherent magnitude, but with
//! two physical bounds. A destructive null is never deeper than a
//! configured floor below the strongest contributing path: a real receiver
//! never sees an infinitely deep null because of finite bandwidth and
//! surface roughness, and crucially this floor is referenced to the
//! strongest path, not to free space, so when every path is blocked the
//! level runs to the obstruction budget and the link genuinely closes
//! rather than being rescued to a hearable level. Constructive build up is
//! capped at a configured gain ceiling so a near lossless enclosure cannot
//! return an unbounded field; real tunnel waveguide gain over free space is
//! bounded, and the ceiling keeps the model within that bound.
//!
//! Image method on axis-aligned planes
//! -----------------------------------
//!
//! Voxel surfaces are axis-aligned planes, so the image method is exact:
//! reflecting the source across a plane perpendicular to one axis flips
//! only that coordinate. A pair of opposing parallel planes, the two walls
//! of a corridor or the floor and ceiling of a room, generates the classic
//! image set at positions s + 2 m w and (2 a - s) + 2 m w for integer m,
//! where a is the lower plane, w the spacing and s the source coordinate.
//! The unfolded straight line from each image to the receiver crosses the
//! periodic wall positions a number of times equal to the bounce order,
//! and the parity of each crossing selects which physical wall it touched,
//! so the product of per-bounce Fresnel coefficients follows directly. A
//! low loss wall keeps the coefficient near unity, so many images survive
//! and the field reverberates; a lossy wall drives the coefficient down and
//! the series collapses after a bounce or two. The decay of the lossy image
//! series is the physical reverberation decay, and a running amplitude
//! early out truncates it once the contribution falls below a threshold,
//! which bounds the cost for ordinary lossy materials while letting a metal
//! enclosure run to the bounce limit.
//!
//! Approximations and their bounds
//! -------------------------------
//!
//! Reflectors are found by probing perpendicular from the path midpoint to
//! the nearest solid surface on each of the six axis directions, and each
//! is treated as an infinite plane at that distance. This is exact for a
//! straight corridor of constant cross section and approximate for
//! irregular geometry; the probe range bounds how far a wall can be and
//! still count, so an open field finds only the ground and reduces to the
//! two-ray result. Reflections are handled independently per axis, so a
//! corner path that reflects off two perpendicular walls in succession is
//! not generated; the dominant single-axis series, the corridor waveguide
//! and the floor to ceiling bounce, is.

use crate::propagation::friis::{wavelength_m, SPEED_OF_LIGHT_M_S};
use crate::propagation::material::{MaterialId, MaterialTable};
use crate::propagation::reflection::{fresnel_reflection, Polarization};
use crate::propagation::voxel::raycast_absorption_db;
use crate::propagation::voxel::VoxelGrid;

/// Which multipath model the solver applies to the ground wave.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum MultipathModel {
    /// No reflection at all: direct ray plus obstruction and curvature.
    None,
    /// Direct ray plus the single two-ray ground reflection, with a
    /// stochastic Rayleigh residual fade. The light default.
    TwoRay,
    /// The full image-method channel described in this module. Subsumes
    /// the ground reflection and replaces the stochastic fade with a
    /// deterministic geometric channel.
    Multipath,
}

/// Tunable bounds for the image-method channel.
#[derive(Copy, Clone, Debug)]
pub struct MultipathParams {
    /// Maximum reflection order in a parallel-plane image series.
    pub max_bounces: u32,
    /// How far, in metres, a wall can be from the path and still count as
    /// a reflector. Open terrain past this finds only the ground.
    pub probe_range_m: f32,
    /// Antenna polarization for the Fresnel coefficients.
    pub polarization: Polarization,
    /// Maximum depth, in decibels, of a destructive interference null,
    /// referenced to the strongest contributing path.
    pub null_floor_db: f32,
    /// Maximum constructive gain, in decibels, of the multipath sum. Caps
    /// the waveguide build up so a near lossless enclosure stays bounded.
    pub max_gain_db: f32,
}

/// Outcome of one channel evaluation.
#[derive(Copy, Clone, Debug)]
pub struct MultipathResult {
    /// Total channel effect in decibels relative to free space, positive
    /// for a net loss and negative for a net gain. Equal to the coherent
    /// level, clamped to the gain ceiling above and to the null floor
    /// (referenced to the strongest path) below.
    pub total_loss_db: f32,
    /// RMS delay spread of the path set, in seconds. Diagnostic.
    pub rms_delay_spread_s: f32,
    /// Number of paths summed, including the direct ray.
    pub path_count: u32,
}

impl MultipathResult {
    const EMPTY: Self = Self { total_loss_db: 0.0, rms_delay_spread_s: 0.0, path_count: 0 };
}

/// Smallest path amplitude worth carrying into the series. Below this a
/// reflected path contributes negligibly and the image series is truncated.
const AMPLITUDE_FLOOR: f64 = 1.0e-3;

/// Fraction of a voxel by which reflection points are nudged off the wall
/// face into the corridor interior so segment raycasts do not graze the
/// reflecting wall itself.
const WALL_NUDGE_FRAC: f32 = 0.02;

/// Phasor and delay-moment accumulator over the path set.
#[derive(Default)]
struct Accum {
    re: f64,
    im: f64,
    max_amp: f64,
    power_sum: f64,
    power_tau_sum: f64,
    power_tau2_sum: f64,
    count: u32,
}

impl Accum {
    fn add(&mut self, amplitude: f64, phase: f64, tau: f64) {
        self.re += amplitude * phase.cos();
        self.im += amplitude * phase.sin();
        let a = amplitude.abs();
        if a > self.max_amp {
            self.max_amp = a;
        }
        let p = amplitude * amplitude;
        self.power_sum += p;
        self.power_tau_sum += p * tau;
        self.power_tau2_sum += p * tau * tau;
        self.count += 1;
    }
}

/// Constant inputs shared by every path computation in one solve.
struct Channel<'a, G: VoxelGrid> {
    grid: &'a G,
    materials: &'a MaterialTable,
    tx: [f32; 3],
    rx: [f32; 3],
    frequency_hz: f64,
    budget_db: f32,
    k_wave: f64,
    c: f64,
    d_los: f64,
    pol: Polarization,
    max_bounces: u32,
}

/// Evaluate the coherent multipath channel between tx and rx.
///
/// `direct_transmission` is the amplitude of the direct ray relative to the
/// unobstructed free space field, the value 10^(-aperture_loss_db/20) the
/// solver already computed; folding it in lets a blocked line of sight drop
/// to near zero while reflected paths still reach the receiver. The returned
/// `total_loss_db` is added to the free space loss in place of both the
/// separate obstruction term and the two-ray reflection term.
pub fn multipath_channel<G: VoxelGrid>(
    grid: &G,
    materials: &MaterialTable,
    tx: [f32; 3],
    rx: [f32; 3],
    frequency_hz: f64,
    budget_db: f32,
    direct_transmission: f32,
    params: &MultipathParams,
) -> MultipathResult {
    let dx = (rx[0] - tx[0]) as f64;
    let dy = (rx[1] - tx[1]) as f64;
    let dz = (rx[2] - tx[2]) as f64;
    let d_los = (dx * dx + dy * dy + dz * dz).sqrt();
    if d_los <= 0.0 {
        return MultipathResult::EMPTY;
    }

    let lambda = wavelength_m(frequency_hz);
    let ch = Channel {
        grid,
        materials,
        tx,
        rx,
        frequency_hz,
        budget_db,
        k_wave: std::f64::consts::TAU / lambda,
        c: SPEED_OF_LIGHT_M_S as f64,
        d_los,
        pol: params.polarization,
        max_bounces: params.max_bounces,
    };

    let mut acc = Accum::default();
    // Direct ray: amplitude is the line of sight obstruction transmission,
    // phase zero, delay the straight line distance.
    acc.add(direct_transmission as f64, 0.0, d_los / ch.c);

    let mid = [
        (tx[0] + rx[0]) * 0.5,
        (tx[1] + rx[1]) * 0.5,
        (tx[2] + rx[2]) * 0.5,
    ];
    let vs = grid.voxel_size_m().max(1e-3);
    let range = ((params.probe_range_m / vs).ceil() as i32).max(1);

    for ax in 0..3usize {
        let lo = probe_axis(grid, mid, ax, -1, range);
        let hi = probe_axis(grid, mid, ax, 1, range);
        accumulate_axis(&mut acc, &ch, ax, lo, hi);
    }

    let coh_mag = (acc.re * acc.re + acc.im * acc.im).sqrt();
    let coh_loss = -20.0 * coh_mag.max(1e-9).log10();
    let strongest_loss = -20.0 * acc.max_amp.max(1e-9).log10();
    let capped = coh_loss.min(strongest_loss + params.null_floor_db as f64);
    let loss = capped.max(-(params.max_gain_db as f64));

    let (mean, var) = if acc.power_sum > 0.0 {
        let m = acc.power_tau_sum / acc.power_sum;
        let v = (acc.power_tau2_sum / acc.power_sum - m * m).max(0.0);
        (m, v)
    } else {
        (0.0, 0.0)
    };
    let _ = mean;

    MultipathResult {
        total_loss_db: loss as f32,
        rms_delay_spread_s: var.sqrt() as f32,
        path_count: acc.count,
    }
}

/// Generate every reflected path off the walls found on one axis. With both
/// a low and a high wall present the source sits in a corridor and the full
/// parallel-plane image series runs; with only one wall a single first-order
/// reflection is generated, the ground bounce of the two-ray model.
fn accumulate_axis<G: VoxelGrid>(
    acc: &mut Accum,
    ch: &Channel<'_, G>,
    ax: usize,
    lo: Option<(f32, MaterialId)>,
    hi: Option<(f32, MaterialId)>,
) {
    match (lo, hi) {
        (Some((a, mat_a)), Some((b, mat_b))) => {
            let eps_a = ch.materials.get(mat_a).complex_permittivity(ch.frequency_hz);
            let eps_b = ch.materials.get(mat_b).complex_permittivity(ch.frequency_hz);
            corridor_series(acc, ch, ax, a, b, eps_a, eps_b);
        }
        (Some((p, mat)), None) => {
            let eps = ch.materials.get(mat).complex_permittivity(ch.frequency_hz);
            single_reflection(acc, ch, ax, p, eps);
        }
        (None, Some((p, mat))) => {
            let eps = ch.materials.get(mat).complex_permittivity(ch.frequency_hz);
            single_reflection(acc, ch, ax, p, eps);
        }
        (None, None) => {}
    }
}

/// One first-order reflection off a single plane at coordinate `p` on axis
/// `ax`. The reflected ray is raycast for obstruction along its two
/// segments through the real grid.
fn single_reflection<G: VoxelGrid>(
    acc: &mut Accum,
    ch: &Channel<'_, G>,
    ax: usize,
    p: f32,
    eps: (f32, f32),
) {
    let mut image = ch.tx;
    image[ax] = 2.0 * p - ch.tx[ax];
    let len = dist3(image, ch.rx);
    if len <= 0.0 {
        return;
    }
    let denom = ch.rx[ax] - image[ax];
    if denom.abs() < 1e-4 {
        return;
    }
    let sin_g = ((ch.rx[ax] - image[ax]).abs() as f64 / len as f64).clamp(0.0, 1.0);
    let psi = sin_g.asin() as f32;
    let (g_mag, g_phase) = fresnel_reflection(eps.0, eps.1, psi, ch.pol);

    let t = (p - image[ax]) / denom;
    let mut refl = [0.0_f32; 3];
    for d in 0..3 {
        refl[d] = image[d] + t * (ch.rx[d] - image[d]);
    }
    refl[ax] = p;
    // Nudge off the wall face toward the source side so the segment
    // raycasts do not graze the reflecting wall.
    let vs = ch.grid.voxel_size_m().max(1e-3);
    refl[ax] += (ch.tx[ax] - p).signum() * WALL_NUDGE_FRAC * vs;

    let abs = polyline_absorption(ch, &[ch.tx, refl, ch.rx]);
    let transmission = 10f64.powf(-(abs as f64) / 20.0);
    let amplitude = (ch.d_los / len as f64) * g_mag as f64 * transmission;
    if amplitude < AMPLITUDE_FLOOR {
        return;
    }
    let phase = g_phase as f64 - ch.k_wave * (len as f64 - ch.d_los);
    acc.add(amplitude, phase, len as f64 / ch.c);
}

/// Full parallel-plane image series between the lower plane `a` and the
/// upper plane `b` on axis `ax`. Each valid image is obstruction checked
/// along its folded polyline and truncated once its amplitude falls below
/// the floor.
fn corridor_series<G: VoxelGrid>(
    acc: &mut Accum,
    ch: &Channel<'_, G>,
    ax: usize,
    a: f32,
    b: f32,
    eps_a: (f32, f32),
    eps_b: (f32, f32),
) {
    let w = b - a;
    if w <= 1e-3 {
        return;
    }
    let s = ch.tx[ax];
    let r = ch.rx[ax];
    let mb = ch.max_bounces as i32;
    let vs = ch.grid.voxel_size_m().max(1e-3);

    // Image positions: type A at s + 2 m w (skipping the direct path at
    // m = 0) and type B at (2 a - s) + 2 m w.
    let mut images: Vec<f32> = Vec::with_capacity((4 * mb + 2) as usize);
    for m in -mb..=mb {
        if m != 0 {
            images.push(s + 2.0 * m as f32 * w);
        }
        images.push((2.0 * a - s) + 2.0 * m as f32 * w);
    }

    let mut image = ch.tx;
    for img_ax in images {
        image[ax] = img_ax;
        let len = dist3(image, ch.rx);
        if len <= 0.0 {
            continue;
        }

        // Wall crossings strictly between the image and the receiver in the
        // unfolded coordinate. Their count is the bounce order; the parity
        // of each crossing selects the physical wall it touched.
        let denom = r - img_ax;
        if denom.abs() < 1e-4 {
            continue;
        }
        let span_lo = img_ax.min(r);
        let span_hi = img_ax.max(r);
        let k_start = ((span_lo - a) / w).floor() as i32 + 1;
        let k_end = ((span_hi - a) / w).ceil() as i32 - 1;
        if k_start > k_end {
            continue;
        }
        let n = (k_end - k_start + 1) as u32;
        if n < 1 || n > ch.max_bounces {
            continue;
        }

        let mut n_a = 0u32;
        let mut n_b = 0u32;
        for k in k_start..=k_end {
            if k.rem_euclid(2) == 0 {
                n_a += 1;
            } else {
                n_b += 1;
            }
        }

        let sin_g = ((r - img_ax).abs() as f64 / len as f64).clamp(0.0, 1.0);
        let psi = sin_g.asin() as f32;
        let (ma, pa) = fresnel_reflection(eps_a.0, eps_a.1, psi, ch.pol);
        let (mb_mag, pb) = fresnel_reflection(eps_b.0, eps_b.1, psi, ch.pol);
        let gamma_mag = (ma as f64).powi(n_a as i32) * (mb_mag as f64).powi(n_b as i32);
        let gamma_phase = n_a as f64 * pa as f64 + n_b as f64 * pb as f64;

        // Amplitude before obstruction; truncate the series here so a lossy
        // wall stops cheaply while a low loss metal wall runs to the limit.
        let pre_amp = gamma_mag * (ch.d_los / len as f64);
        if pre_amp < AMPLITUDE_FLOOR {
            continue;
        }

        // Folded polyline through the real grid for obstruction.
        let mut pts: Vec<[f32; 3]> = Vec::with_capacity(n as usize + 2);
        pts.push(ch.tx);
        let mut folds: Vec<(f32, bool)> = Vec::with_capacity(n as usize);
        for k in k_start..=k_end {
            let wall = a + k as f32 * w;
            let t = (wall - img_ax) / denom;
            let is_a = k.rem_euclid(2) == 0;
            folds.push((t, is_a));
        }
        folds.sort_by(|x, y| x.0.partial_cmp(&y.0).unwrap());
        for (t, is_a) in &folds {
            let mut p = [0.0_f32; 3];
            for d in 0..3 {
                p[d] = image[d] + t * (ch.rx[d] - image[d]);
            }
            if *is_a {
                p[ax] = a + WALL_NUDGE_FRAC * vs;
            } else {
                p[ax] = b - WALL_NUDGE_FRAC * vs;
            }
            pts.push(p);
        }
        pts.push(ch.rx);

        let abs = polyline_absorption(ch, &pts);
        let transmission = 10f64.powf(-(abs as f64) / 20.0);
        let amplitude = pre_amp * transmission;
        if amplitude < AMPLITUDE_FLOOR {
            continue;
        }
        let phase = gamma_phase - ch.k_wave * (len as f64 - ch.d_los);
        acc.add(amplitude, phase, len as f64 / ch.c);
    }
}

/// Sum the absorption in decibels along a polyline through the grid,
/// passing the remaining budget into each segment so the cumulative cap is
/// honoured. Returns the budget once it is exhausted, which collapses the
/// path transmission to near zero.
fn polyline_absorption<G: VoxelGrid>(ch: &Channel<'_, G>, points: &[[f32; 3]]) -> f32 {
    let mut total = 0.0_f32;
    for seg in points.windows(2) {
        let remaining = ch.budget_db - total;
        if remaining <= 0.0 {
            return ch.budget_db;
        }
        total += raycast_absorption_db(
            ch.grid,
            ch.materials,
            seg[0],
            seg[1],
            ch.frequency_hz,
            remaining,
        );
        if total >= ch.budget_db {
            return ch.budget_db;
        }
    }
    total
}

/// Probe outward from `from` along axis `ax` in the given `sign` direction
/// for the nearest solid voxel within `range` voxels. Returns the
/// reflecting plane coordinate (the solid voxel face toward the source) and
/// the surface material, or None if open within range.
fn probe_axis<G: VoxelGrid>(
    grid: &G,
    from: [f32; 3],
    ax: usize,
    sign: i32,
    range: i32,
) -> Option<(f32, MaterialId)> {
    let vs = grid.voxel_size_m().max(1e-3);
    let mut v = [
        (from[0] / vs).floor() as i32,
        (from[1] / vs).floor() as i32,
        (from[2] / vs).floor() as i32,
    ];
    for _ in 0..range {
        v[ax] += sign;
        let id = grid.material_at(v[0], v[1], v[2]);
        if id != MaterialId::AIR {
            let plane = if sign > 0 {
                v[ax] as f32 * vs
            } else {
                (v[ax] + 1) as f32 * vs
            };
            return Some((plane, id));
        }
    }
    None
}

fn dist3(a: [f32; 3], b: [f32; 3]) -> f32 {
    let dx = b[0] - a[0];
    let dy = b[1] - a[1];
    let dz = b[2] - a[2];
    (dx * dx + dy * dy + dz * dz).sqrt()
}