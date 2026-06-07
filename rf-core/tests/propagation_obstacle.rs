//! Solve the link budget through a set of terrain configurations under
//! both the TwoRay and Multipath models, to verify that blocks block and
//! reflections help.
//!
//! Run: cargo run --example propagation_obstacle
//!
//! What to look for:
//!   * clear          line of sight open, obstruction near zero, the
//!                    ground reflection contributes a small term.
//!   * block_in_air   a single iron voxel on the line of sight partially
//!                    obstructs (the signal diffracts around it through
//!                    the open Fresnel zone); it does not close the path.
//!   * iron_wall      iron filling the whole first Fresnel zone closes the
//!                    path: the obstruction runs to the budget and the
//!                    Multipath model does not rescue it, because every
//!                    reflected path into the receiver also crosses iron.
//!   * stone_ridge    a partial stone ridge attenuates without closing.
//!   * iron_tunnel    a low loss metal tube waveguides: the Multipath
//!                    reflection term is favourable (a gain), so the link
//!                    is stronger than the two-ray ground bounce alone.
//!   * stone_tunnel   a lossy stone tube reflects weakly, so the waveguide
//!                    gain is small; the image series collapses after a
//!                    bounce or two.
//!
//! Read the reflection column: a negative value is reflected energy adding
//! to the link (waveguiding, constructive interference), a positive value
//! is a multipath null. The absorption column is the line of sight
//! obstruction. The Multipath total folds both in.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialId, MaterialTable, MultipathModel, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};

const MAT_STONE: u16 = 1;
const MAT_IRON: u16 = 7;
const MAT_DIRT: u16 = 5;

fn main() {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let scenes: &[(&str, f32, fn(f32) -> DenseVoxelGrid)] = &[
        ("clear", 200.0, build_clear),
        ("block_in_air", 200.0, build_block_in_air),
        ("iron_wall", 200.0, build_iron_wall),
        ("stone_ridge", 200.0, build_stone_ridge),
        ("iron_tunnel", 120.0, build_iron_tunnel),
        ("stone_tunnel", 120.0, build_stone_tunnel),
    ];

    for model in [MultipathModel::TwoRay, MultipathModel::Multipath] {
        println!("model: {model:?}");
        println!(
            "  {:<14} {:>8} {:>8} {:>8} {:>8} {:>10}",
            "scene", "total", "abs", "refl", "curv", "linear"
        );
        for (label, d, builder) in scenes {
            let grid = builder(*d);
            let mut cfg = SolverConfig::default();
            cfg.multipath_model = model;
            let solver = PropagationSolver::new(&grid, &materials, &iono, cfg);
            let mut fading = RayleighFading::new(0xC0FFEE, 0.0);
            let inputs = PropagationInputs {
                tx_pos: [0.0, 5.0, 0.0],
                rx_pos: [*d, 5.0, 0.0],
                tx_vel: [0.0; 3],
                rx_vel: [0.0; 3],
                frequency_hz: 100.0e6,
                tx_gain: 1.0,
                rx_gain: 1.0,
                local_hour: 12.0,
            };
            let pl = solver.solve(inputs, &mut fading);
            println!(
                "  {:<14} {:>8.1} {:>8.1} {:>8.1} {:>8.1} {:>10.3e}",
                label,
                pl.db,
                pl.components.absorption_db,
                pl.components.reflection_db,
                pl.components.diffraction_db,
                pl.linear
            );
        }
        println!();
    }
}

/// Lay a flat dirt floor with the top face at y = 0 over the whole scene.
fn with_ground(reach: f32) -> DenseVoxelGrid {
    let span = reach.ceil() as i32 + 20;
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -20], [span as u32, 50, 40]);
    for x in -8..(span - 8) {
        for z in -18..18 {
            for y in -4..0 {
                g.set(x, y, z, MaterialId(MAT_DIRT));
            }
        }
    }
    g
}

fn build_clear(reach: f32) -> DenseVoxelGrid {
    with_ground(reach)
}

fn build_block_in_air(reach: f32) -> DenseVoxelGrid {
    let mut g = with_ground(reach);
    let xm = (reach * 0.5) as i32;
    g.set(xm, 5, 0, MaterialId(MAT_IRON));
    g
}

fn build_iron_wall(reach: f32) -> DenseVoxelGrid {
    let mut g = with_ground(reach);
    let xm = (reach * 0.5) as i32;
    for x in xm..(xm + 2) {
        for y in -8..40 {
            for z in -18..18 {
                g.set(x, y, z, MaterialId(MAT_IRON));
            }
        }
    }
    g
}

fn build_stone_ridge(reach: f32) -> DenseVoxelGrid {
    let mut g = with_ground(reach);
    let xm = (reach * 0.5) as i32;
    for x in (xm - 10)..(xm + 10) {
        for y in 0..30 {
            for z in -5..5 {
                g.set(x, y, z, MaterialId(MAT_STONE));
            }
        }
    }
    g
}

fn tunnel(reach: f32, material: u16) -> DenseVoxelGrid {
    let span = reach.ceil() as i32 + 20;
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -20], [span as u32, 50, 40]);
    for x in -8..(span - 8) {
        for y in -4..14 {
            for z in -8..8 {
                // Interior: y in [1, 9], z in [-4, 4]. Everything else solid.
                let interior = (1..=9).contains(&y) && (-4..=4).contains(&z);
                if !interior {
                    g.set(x, y, z, MaterialId(material));
                }
            }
        }
    }
    g
}

fn build_iron_tunnel(reach: f32) -> DenseVoxelGrid {
    tunnel(reach, MAT_IRON)
}

fn build_stone_tunnel(reach: f32) -> DenseVoxelGrid {
    tunnel(reach, MAT_STONE)
}