//! Earth curvature and radio horizon checks.

use rf_core::propagation::{
    curvature_loss_db, link_radio_horizon_m, radio_horizon_m,
    DenseVoxelGrid, IonosphereLut, MaterialTable, PropagationInputs, PropagationSolver,
    RayleighFading, SolarActivity, SolverConfig, MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR,
};

#[test]
fn single_horizon_matches_textbook() {
    // 4.12 * sqrt(h) km for h in metres, standard 4/3 earth.
    let d = radio_horizon_m(10.0, MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    let expected = 4120.0 * (10.0_f32).sqrt();
    assert!((d - expected).abs() < expected * 0.02, "got {d}, expected {expected}");
}

#[test]
fn within_horizon_has_no_curvature_loss() {
    // Two 50 m antennas, 5 km apart: well inside the link horizon, so
    // the bulge stays below the line of sight and the loss is zero.
    let loss = curvature_loss_db(50.0, 50.0, 5_000.0, 145.0e6,
        MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    assert_eq!(loss, 0.0, "got {loss}");
}

#[test]
fn beyond_horizon_has_loss() {
    // Two 2 m antennas have a link horizon near 11.6 km. At 30 km the
    // bulge is deep into the path and the loss is substantial.
    let horizon = link_radio_horizon_m(2.0, 2.0, MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    assert!(horizon < 13_000.0, "horizon {horizon} unexpectedly large");
    let loss = curvature_loss_db(2.0, 2.0, 30_000.0, 145.0e6,
        MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    assert!(loss > 15.0, "beyond-horizon loss too small: {loss}");
}

#[test]
fn higher_antennas_reach_further() {
    // At a fixed distance, taller antennas clear the bulge that shorter
    // ones are blocked by, so they suffer less curvature loss.
    let low = curvature_loss_db(2.0, 2.0, 20_000.0, 145.0e6,
        MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    let high = curvature_loss_db(100.0, 100.0, 20_000.0, 145.0e6,
        MEAN_EARTH_RADIUS_M, STANDARD_K_FACTOR);
    assert!(high < low, "tall {high} should beat short {low}");
}

#[test]
fn solver_closes_long_ground_path_but_not_short() {
    let grid = DenseVoxelGrid::new(1.0, [-50, 0, -50], [100, 200, 100]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    let mut fading = RayleighFading::new(1, 0.0);
    let solver = PropagationSolver::new(&grid, &materials, &iono, SolverConfig::default());

    // Ground-level VHF radios (Y at the sea-level reference, height
    // floored to 1 m). Curvature lands in the diffraction component.
    let make = |dist: f32| PropagationInputs {
        tx_pos: [0.0, 63.0, 0.0],
        rx_pos: [dist, 63.0, 0.0],
        tx_vel: [0.0; 3],
        rx_vel: [0.0; 3],
        frequency_hz: 145.0e6,
        tx_gain: 1.0,
        rx_gain: 1.0,
        local_hour: 12.0,
    };
    let near = solver.solve(make(2_000.0), &mut fading).components.diffraction_db;
    let far = solver.solve(make(40_000.0), &mut fading).components.diffraction_db;
    assert!(near < 1.0, "2 km should be within horizon, got {near}");
    assert!(far > 20.0, "40 km should be deep past horizon, got {far}");
}