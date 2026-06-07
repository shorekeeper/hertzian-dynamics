//! Coherent multipath: the image-method channel and its solver
//! integration.

use rf_core::propagation::{
    ChunkedVoxelGrid, IonosphereLut, MaterialId, MaterialTable, MultipathModel, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};
use rf_core::PathLoss;

/// A hollow rectangular tube of the given wall material along the X axis.
/// Interior: y in [1, 9], z in [-3, 3]. Floor, ceiling and the two lateral
/// walls are solid out to a shell, so the midpoint probe finds a reflector
/// on every lateral and vertical direction.
fn tube(reach_x: i32, material: u16) -> ChunkedVoxelGrid {
    let mut g = ChunkedVoxelGrid::new(1.0);
    for x in -3..(reach_x + 3) {
        for y in -2..13 {
            for z in -7..8 {
                let interior = (1..=9).contains(&y) && (-3..=3).contains(&z);
                if !interior {
                    g.set(x, y, z, MaterialId(material));
                }
            }
        }
    }
    g
}

/// Flat dirt ground with the top face at y = 0, no walls.
fn open_ground(reach_x: i32) -> ChunkedVoxelGrid {
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

/// A receiver sealed inside a solid iron box, transmitter outside.
fn sealed_box() -> ChunkedVoxelGrid {
    let mut g = ChunkedVoxelGrid::new(1.0);
    // Iron shell around a small interior cavity centred at x = 150.
    for x in 145..156 {
        for y in 0..11 {
            for z in -5..6 {
                let interior = (146..=154).contains(&x)
                    && (1..=9).contains(&y)
                    && (-4..=4).contains(&z);
                if !interior {
                    g.set(x, y, z, MaterialId(7)); // iron
                }
            }
        }
    }
    // A floor for the outside path.
    for x in -5..156 {
        for z in -6..6 {
            for y in -3..0 {
                g.set(x, y, z, MaterialId(5));
            }
        }
    }
    g
}

fn solve(grid: &ChunkedVoxelGrid, cfg: SolverConfig, tx: [f32; 3], rx: [f32; 3]) -> PathLoss {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    let mut fading = RayleighFading::new(0xC0FFEE, 0.0);
    let solver = PropagationSolver::new(grid, &materials, &iono, cfg);
    let inputs = PropagationInputs {
        tx_pos: tx,
        rx_pos: rx,
        tx_vel: [0.0; 3],
        rx_vel: [0.0; 3],
        frequency_hz: 100.0e6,
        tx_gain: 1.0,
        rx_gain: 1.0,
        local_hour: 12.0,
    };
    solver.solve(inputs, &mut fading)
}

fn multipath_cfg() -> SolverConfig {
    let mut cfg = SolverConfig::default();
    cfg.multipath_model = MultipathModel::Multipath;
    cfg
}

#[test]
fn open_field_multipath_reduces_to_ground_reflection() {
    let grid = open_ground(2000);
    let p = solve(&grid, multipath_cfg(), [0.0, 5.0, 0.0], [2000.0, 5.0, 0.0]);
    assert!(p.components.absorption_db < 1.0, "obstruction {}", p.components.absorption_db);
    assert!(p.components.reflection_db.abs() > 0.5, "reflection {}", p.components.reflection_db);
    assert!(!p.is_closed());
}

#[test]
fn metal_tube_waveguides_more_than_stone_tube() {
    // A low loss metal tube keeps the reflection coefficient near unity, so
    // many images survive and the field builds up: the reflection
    // contribution is more favourable than the same tube built from lossy
    // stone, where the image series collapses.
    let iron = solve(&tube(150, 7), multipath_cfg(), [0.0, 5.0, 0.0], [150.0, 5.0, 0.0]);
    let stone = solve(&tube(150, 1), multipath_cfg(), [0.0, 5.0, 0.0], [150.0, 5.0, 0.0]);
    assert!(
        iron.components.reflection_db < stone.components.reflection_db - 1.0,
        "iron {} stone {}",
        iron.components.reflection_db,
        stone.components.reflection_db
    );
    assert!(!iron.is_closed());
}

#[test]
fn metal_tube_produces_delay_spread() {
    let p = solve(&tube(150, 7), multipath_cfg(), [0.0, 5.0, 0.0], [150.0, 5.0, 0.0]);
    assert!(p.rms_delay_spread_s > 0.0, "delay spread {}", p.rms_delay_spread_s);
}

#[test]
fn multipath_gain_is_capped() {
    let mut cfg = multipath_cfg();
    cfg.multipath_max_gain_db = 8.0;
    let p = solve(&tube(150, 7), cfg, [0.0, 5.0, 0.0], [150.0, 5.0, 0.0]);
    let total_multipath = p.components.absorption_db + p.components.reflection_db;
    assert!(total_multipath >= -8.0 - 0.01, "total multipath {}", total_multipath);
}

#[test]
fn none_mode_has_no_reflection_or_spread() {
    let mut cfg = SolverConfig::default();
    cfg.multipath_model = MultipathModel::None;
    let p = solve(&open_ground(2000), cfg, [0.0, 5.0, 0.0], [2000.0, 5.0, 0.0]);
    assert_eq!(p.components.reflection_db, 0.0);
    assert_eq!(p.rms_delay_spread_s, 0.0);
}

#[test]
fn iron_wall_closes_path_even_in_multipath() {
    // An iron wall filling the first Fresnel zone closes the path under the
    // Multipath model: the reflections cannot rescue a fully blocked line of
    // sight, because every reflected path into the receiver also crosses
    // iron. The null floor is referenced to the strongest path, not to free
    // space, so a fully blocked link runs to the obstruction budget rather
    // than being clamped to a hearable level.
    let mut g = ChunkedVoxelGrid::new(1.0);
    for x in -5..205 {
        for z in -20..20 {
            for y in -3..0 {
                g.set(x, y, z, MaterialId(5)); // dirt floor
            }
        }
    }
    for x in 99..101 {
        for y in -10..40 {
            for z in -18..18 {
                g.set(x, y, z, MaterialId(7)); // iron wall
            }
        }
    }
    let p = solve(&g, multipath_cfg(), [0.0, 5.0, 0.0], [200.0, 5.0, 0.0]);
    let total_multipath = p.components.absorption_db + p.components.reflection_db;
    assert!(total_multipath > 200.0, "total multipath {}", total_multipath);
    assert!(p.is_closed(), "path should be closed, db {}", p.db);
}

#[test]
fn sealed_receiver_is_silent_in_multipath() {
    // A receiver sealed in iron hears nothing: there is no clear reflected
    // path into the cavity, so the channel collapses and the link closes.
    let grid = sealed_box();
    let p = solve(&grid, multipath_cfg(), [0.0, 5.0, 0.0], [150.0, 5.0, 0.0]);
    assert!(p.is_closed(), "sealed receiver should be silent, db {}", p.db);
}