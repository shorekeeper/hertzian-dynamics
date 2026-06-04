//! Voxel raycast.

use rf_core::propagation::{
    raycast_absorption_db, DenseVoxelGrid, Material, MaterialId, MaterialTable, VoxelGrid,
};

fn empty_grid() -> DenseVoxelGrid {
    DenseVoxelGrid::new(1.0, [-50, -10, -50], [100, 30, 100])
}

#[test]
fn empty_air_path_has_zero_absorption() {
    let grid = empty_grid();
    let tab = MaterialTable::with_defaults();
    let db = raycast_absorption_db(&grid, &tab, [0.0, 5.0, 0.0], [50.0, 5.0, 0.0], 100.0e6, 120.0);
    assert!(db.abs() < 1e-3, "got {db}");
}

#[test]
fn two_metres_of_stone_yields_about_fifty_db_at_100mhz() {
    // Stone is calibrated at 25 dB/m for 1 m solid voxels (a deliberate
    // gameplay value, not a textbook constant; see MaterialTable
    // with_defaults). Two metres of it on the direct ray therefore costs
    // about 50 dB, which stays clear of the 120 dB budget so the test
    // exercises linear accumulation rather than the clamp. A thicker wall
    // would saturate against the budget and tell us nothing about the
    // per-metre rate.
    let mut grid = empty_grid();
    for x in 10..12 {
        for z in -1..2 {
            grid.set(x, 5, z, MaterialId(1));
        }
    }
    let tab = MaterialTable::with_defaults();
    let db = raycast_absorption_db(&grid, &tab, [0.0, 5.5, 0.0], [50.0, 5.5, 0.0], 100.0e6, 120.0);
    assert!((db - 50.0).abs() < 2.0, "got {db}");
}

#[test]
fn opaque_iron_wall_hits_budget() {
    let mut grid = empty_grid();
    for x in 10..15 {
        for y in -5..15 {
            for z in -5..5 {
                grid.set(x, y, z, MaterialId(7));
            }
        }
    }
    let tab = MaterialTable::with_defaults();
    let db = raycast_absorption_db(&grid, &tab, [0.0, 5.0, 0.0], [50.0, 5.0, 0.0], 100.0e6, 120.0);
    assert!(db >= 119.99, "got {db}");
}

#[test]
fn custom_material_registers_and_attenuates() {
    let mut tab = MaterialTable::with_defaults();
    tab.register(Material {
        id: MaterialId(8),
        name: "lead",
        atten_db_per_m_at_ref: 80.0,
        reference_frequency_hz: 100e6,
        scaling_exponent: 0.5,
        pivot_frequency_hz: 30e6,
    });
    let mat = tab.get(MaterialId(8));
    let db = mat.attenuation_db_per_m(100e6);
    assert!((db - 80.0).abs() < 0.5);
    let db_hf = mat.attenuation_db_per_m(10e6);
    assert!((db_hf - 80.0).abs() < 0.5, "below pivot should not scale: {db_hf}");
}