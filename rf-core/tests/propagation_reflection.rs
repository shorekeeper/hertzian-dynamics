//! Ground reflection: Fresnel coefficients, the two-ray model, and the
//! solver integration.

use rf_core::propagation::{
    fresnel_reflection, two_ray_gain_db, ChunkedVoxelGrid, IonosphereLut, MaterialId,
    MaterialTable, MultipathModel, Polarization, PropagationInputs, PropagationSolver,
    RayleighFading, SolarActivity, SolverConfig,
};

use rf_core::PathLoss;

// Conductivity term for iron at 100 MHz, sigma / (omega * eps0). Iron is a
// near perfect reflector, so its imaginary permittivity is enormous.
fn iron_eps_imag_at_100mhz() -> f32 {
    let sigma = 1.0e7_f64;
    let omega = std::f64::consts::TAU * 100.0e6;
    let eps0 = 8.854_187_812_8e-12;
    (sigma / (omega * eps0)) as f32
}

#[test]
fn perfect_conductor_horizontal_reflects_with_phase_pi() {
    // A good conductor at a small grazing angle gives Gamma close to -1
    // for horizontal polarization: unit magnitude, phase pi.
    let (mag, phase) = fresnel_reflection(1.0, iron_eps_imag_at_100mhz(), 0.1, Polarization::Horizontal);
    assert!((mag - 1.0).abs() < 0.02, "magnitude {mag}");
    assert!((phase.abs() - std::f32::consts::PI).abs() < 0.1, "phase {phase}");
}

#[test]
fn perfect_conductor_vertical_reflects_constructively_off_grazing() {
    // Off grazing, above the (vanishingly small) pseudo-Brewster angle of
    // a good conductor, vertical polarization reflects with Gamma near +1:
    // unit magnitude, phase near zero, the two rays adding rather than
    // cancelling. This is the polarization split the full Fresnel
    // coefficient captures.
    let (mag, phase) = fresnel_reflection(1.0, iron_eps_imag_at_100mhz(), 0.1, Polarization::Vertical);
    assert!((mag - 1.0).abs() < 0.02, "magnitude {mag}");
    assert!(phase.abs() < 0.1, "phase {phase}");
}

#[test]
fn dirt_vertical_at_grazing_is_near_minus_one() {
    // Over real ground at a very grazing angle, below its pseudo-Brewster
    // angle, vertical polarization also tends toward -1, though with
    // magnitude below unity because the lossy ground absorbs part of the
    // wave.
    let eps_im_dirt = {
        let sigma = 0.005_f64;
        let omega = std::f64::consts::TAU * 100.0e6;
        let eps0 = 8.854_187_812_8e-12;
        (sigma / (omega * eps0)) as f32
    };
    let (mag, phase) = fresnel_reflection(13.0, eps_im_dirt, 0.01, Polarization::Vertical);
    assert!(mag > 0.8 && mag < 1.0, "magnitude {mag}");
    assert!((phase.abs() - std::f32::consts::PI).abs() < 0.2, "phase {phase}");
}

#[test]
fn two_ray_follows_d4_slope_over_conductor() {
    // Horizontal polarization over a perfect reflector gives Gamma = -1
    // and the clean d^4 law. Past the critical distance the reflection
    // loss grows as 20 log10(d), so a factor of three in distance adds
    // 20 log10(3) ~ 9.54 dB of loss (the gain falls by that much). The
    // null floor is set high so it does not clamp the comparison.
    let eps_im = iron_eps_imag_at_100mhz();
    let lambda = 2.998_f32; // 100 MHz
    let near = two_ray_gain_db(1.0, eps_im, 10.0, 10.0, 2000.0, lambda, 0.0, Polarization::Horizontal, 100.0);
    let far = two_ray_gain_db(1.0, eps_im, 10.0, 10.0, 6000.0, lambda, 0.0, Polarization::Horizontal, 100.0);
    let expected = 20.0 * (3.0_f32).log10();
    assert!(((near - far) - expected).abs() < 0.6, "near {near} far {far}");
}

#[test]
fn two_ray_taller_antennas_reduce_loss() {
    // At fixed range in the d^4 regime, raising the antennas reduces the
    // reflection loss (the -20 log10(h_t h_r) term), so the gain rises.
    let eps_im = iron_eps_imag_at_100mhz();
    let lambda = 2.998_f32;
    let low = two_ray_gain_db(1.0, eps_im, 5.0, 5.0, 2000.0, lambda, 0.0, Polarization::Horizontal, 100.0);
    let high = two_ray_gain_db(1.0, eps_im, 20.0, 20.0, 2000.0, lambda, 0.0, Polarization::Horizontal, 100.0);
    assert!(high > low + 10.0, "low {low} high {high}");
}

#[test]
fn blocked_reflected_ray_collapses_to_direct_only() {
    // A wall on the reflected ray attenuates its phasor to nothing, so the
    // correction factor returns to unity and the gain to zero.
    let eps_im = iron_eps_imag_at_100mhz();
    let lambda = 2.998_f32;
    let gain = two_ray_gain_db(1.0, eps_im, 10.0, 10.0, 2000.0, lambda, 200.0, Polarization::Horizontal, 100.0);
    assert!(gain.abs() < 0.01, "gain {gain}");
}

/// Sparse grid with a flat dirt surface whose top face sits at y = 0,
/// covering the path from the transmitter to the receiver.
fn ground_grid(reach_x: i32) -> ChunkedVoxelGrid {
    let mut g = ChunkedVoxelGrid::new(1.0);
    for x in -5..(reach_x + 5) {
        for z in -6..6 {
            for y in -3..0 {
                g.set(x, y, z, MaterialId(5)); // dirt
            }
        }
    }
    g
}

fn solve_over_ground(grid: &ChunkedVoxelGrid, cfg: SolverConfig, d: f32) -> PathLoss {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    let mut fading = RayleighFading::new(0xC0FFEE, 0.0);
    let solver = PropagationSolver::new(grid, &materials, &iono, cfg);
    let inputs = PropagationInputs {
        tx_pos: [0.0, 10.0, 0.0],
        rx_pos: [d, 10.0, 0.0],
        tx_vel: [0.0; 3],
        rx_vel: [0.0; 3],
        frequency_hz: 100.0e6,
        tx_gain: 1.0,
        rx_gain: 1.0,
        local_hour: 12.0,
    };
    solver.solve(inputs, &mut fading)
}

#[test]
fn solver_reflection_activates_over_ground() {
    // With a ground present, a long ground wave path picks up a non
    // trivial reflection contribution, and the aperture clamp keeps the
    // obstruction near zero so the ground is not double counted.
    let grid = ground_grid(2000);
    let p = solve_over_ground(&grid, SolverConfig::default(), 2000.0);
    assert!(p.components.reflection_db.abs() > 1.0, "reflection {}", p.components.reflection_db);
    assert!(p.components.absorption_db < 1.0, "absorption {}", p.components.absorption_db);
}

#[test]
fn solver_reflection_disabled_is_zero() {
    let grid = ground_grid(2000);
    let mut cfg = SolverConfig::default();
    cfg.multipath_model = MultipathModel::None;
    let p = solve_over_ground(&grid, cfg, 2000.0);
    assert_eq!(p.components.reflection_db, 0.0);
}