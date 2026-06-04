//! Solver-level obstruction behaviour. The Fresnel aperture model must
//! make obstacles beside the line of sight matter, let the signal
//! diffract around a small block on the line, and still close the path
//! when a wall spans the whole zone.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialId, MaterialTable, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};

/// Returns the pure obstruction loss (no fade, no noise) for a path.
fn solve_obstruction_db(grid: &DenseVoxelGrid, tx: [f32; 3], rx: [f32; 3], freq: f64) -> f32 {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    // Fresh fader per call: the first draw is identical, so the deducted
    // fade is the same constant across comparisons. We read the
    // obstruction component directly, which carries no fade anyway.
    let mut fading = RayleighFading::new(1, 0.0);
    let solver = PropagationSolver::new(grid, &materials, &iono, SolverConfig::default());
    let inputs = PropagationInputs {
        tx_pos: tx,
        rx_pos: rx,
        tx_vel: [0.0; 3],
        rx_vel: [0.0; 3],
        frequency_hz: freq,
        tx_gain: 1.0,
        rx_gain: 1.0,
        local_hour: 12.0,
    };
    solver.solve(inputs, &mut fading).components.absorption_db
}

#[test]
fn off_axis_block_attenuates() {
    let clear = {
        let g = DenseVoxelGrid::new(1.0, [-20, 0, -20], [80, 40, 40]);
        solve_obstruction_db(&g, [0.0, 20.0, 0.0], [40.0, 20.0, 0.0], 145.0e6)
    };
    let mut grid = DenseVoxelGrid::new(1.0, [-20, 0, -20], [80, 40, 40]);
    // Iron slab a couple of metres to the +z side of the line of sight,
    // never touching it. The old single ray model saw nothing here.
    for y in 12..29 {
        for z in 1..3 {
            grid.set(20, y, z, MaterialId(7));
        }
    }
    let blocked = solve_obstruction_db(&grid, [0.0, 20.0, 0.0], [40.0, 20.0, 0.0], 145.0e6);
    assert!(clear < 0.5, "clear path should be near zero, got {clear}");
    assert!(blocked > clear + 1.0, "off-axis block should add loss, got {blocked}");
}

#[test]
fn single_on_axis_block_does_not_fully_close_long_path() {
    let mut grid = DenseVoxelGrid::new(1.0, [-40, 0, -200], [80, 40, 400]);
    // One iron block on the line at the midpoint of a long path. The
    // Fresnel zone here is far wider than the block, so the signal must
    // diffract around it rather than be hard blocked.
    grid.set(0, 20, 0, MaterialId(7));
    let loss = solve_obstruction_db(&grid, [0.0, 20.0, -150.0], [0.0, 20.0, 150.0], 145.0e6);
    assert!(loss > 0.0, "the block should still cost something, got {loss}");
    assert!(loss < 20.0, "single block on a long path should not close it, got {loss}");
}

#[test]
fn wall_across_zone_closes_path() {
    let mut grid = DenseVoxelGrid::new(1.0, [-20, 0, -20], [80, 40, 40]);
    // A wall spanning the whole cross section at the midpoint. With the
    // entire Fresnel zone blocked there is no way around, so the
    // obstruction loss runs to the budget and the path closes.
    for y in 0..40 {
        for z in -20..20 {
            grid.set(20, y, z, MaterialId(7));
        }
    }
    let loss = solve_obstruction_db(&grid, [0.0, 20.0, 0.0], [40.0, 20.0, 0.0], 145.0e6);
    assert!(loss > 100.0, "full wall should close the path, got {loss}");
}