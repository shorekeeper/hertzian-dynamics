//! Solver obstruction through terrain.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialId, MaterialTable, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};
use rf_core::PathLoss;

fn solve(grid: &DenseVoxelGrid) -> PathLoss {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    let mut fading = RayleighFading::new(0xC0FFEE, 0.0);
    let solver = PropagationSolver::new(grid, &materials, &iono, SolverConfig::default());
    let inputs = PropagationInputs {
        tx_pos: [0.0, 5.0, 0.0],
        rx_pos: [200.0, 5.0, 0.0],
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
fn clear_path_has_no_obstruction() {
    let grid = DenseVoxelGrid::new(1.0, [-10, -10, -10], [240, 50, 30]);
    let p = solve(&grid);
    assert!(p.components.absorption_db < 1.0, "got {}", p.components.absorption_db);
    assert!(!p.is_closed());
}

#[test]
fn partial_stone_ridge_attenuates_without_closing() {
    // A stone ridge that covers only part of the first Fresnel zone. The
    // central rays cross 20 m of stone while the zone edges stay clear,
    // so the signal diffracts around it: real loss, but the path holds.
    let mut grid = DenseVoxelGrid::new(1.0, [-10, -10, -10], [240, 50, 30]);
    for x in 90..110 {
        for y in 0..30 {
            for z in -5..5 {
                grid.set(x, y, z, MaterialId(1));
            }
        }
    }
    let p = solve(&grid);
    assert!(p.components.absorption_db > 1.0, "ridge should attenuate: {}", p.components.absorption_db);
    assert!(!p.is_closed(), "ridge leaves the zone edges open");
}

#[test]
fn full_iron_wall_closes_the_path() {
    // The path closes only when iron fills the entire first Fresnel zone
    // with margin, the infinite screen limit. The zone radius at midpath
    // is r1 = 0.5*sqrt(lambda*d) = 0.5*sqrt(2.998*200) ~ 12.2 m around the
    // line of sight at (y=5, z=0). The grid and wall extend well past that
    // on every side so no sampled ray of the aperture bundle can clip an
    // out of bounds voxel (treated as air) and leak through. A finite
    // screen flush with the grid edge would instead let the field diffract
    // around the open boundary, which is the correct physics of a finite
    // obstacle but not a closed path. Every sampled ray then crosses iron
    // and saturates the absorption budget, so the obstruction runs to it.
    let mut grid = DenseVoxelGrid::new(1.0, [-10, -18, -18], [240, 40, 40]);
    for x in 99..101 {
        for y in -18..22 {
            for z in -18..22 {
                grid.set(x, y, z, MaterialId(7));
            }
        }
    }
    let p = solve(&grid);
    assert!(p.components.absorption_db > 250.0, "wall should saturate: {}", p.components.absorption_db);
}