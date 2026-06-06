//! Voxel raycast.

use rf_core::propagation::{
    raycast_absorption_db, DenseVoxelGrid, Material, MaterialId, MaterialTable,
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
fn two_metres_of_stone_attenuates_per_metre_rate() {
    // Stone at 100 MHz derives to about 2.69 dB/m from its permittivity
    // and conductivity (eps_r 5.3, sigma ~0.0038 S/m). Two voxels on the
    // direct ray cost about 5.4 dB, well below the 120 dB budget, so the
    // test exercises linear accumulation rather than the clamp.
    let mut grid = empty_grid();
    for x in 10..12 {
        for z in -1..2 {
            grid.set(x, 5, z, MaterialId(1));
        }
    }
    let tab = MaterialTable::with_defaults();
    let db = raycast_absorption_db(&grid, &tab, [0.0, 5.5, 0.0], [50.0, 5.5, 0.0], 100.0e6, 120.0);
    assert!((db - 5.38).abs() < 0.25, "got {db}");
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
fn custom_conductor_follows_skin_effect_trend() {
    let mut tab = MaterialTable::with_defaults();
    tab.register(Material {
        id: MaterialId(8),
        name: "custom-metal",
        eps_a: 3.0,
        eps_b: 0.0,
        sigma_c: 1.0,
        sigma_d: 0.0,
        pivot_frequency_hz: 1.0e6,
    });
    let m = tab.get(MaterialId(8));
    let low = m.attenuation_db_per_m(100e6);
    let high = m.attenuation_db_per_m(400e6);
    assert!(low > 0.0, "conductor must attenuate: {low}");
    // Conductivity dominated loss grows as the square root of frequency,
    // so quadrupling the frequency doubles the attenuation.
    let ratio = high / low;
    assert!((ratio - 2.0).abs() < 0.05, "skin effect ratio {ratio}");
}

#[test]
fn pivot_clamps_property_evaluation() {
    let mut tab = MaterialTable::with_defaults();
    tab.register(Material {
        id: MaterialId(9),
        name: "steep",
        eps_a: 4.0,
        eps_b: 0.0,
        sigma_c: 0.1,
        sigma_d: 1.0,
        pivot_frequency_hz: 50.0e6,
    });
    let m = tab.get(MaterialId(9));
    // Above the pivot the conductivity follows sigma = 0.1 * f_GHz.
    let above = m.conductivity(100e6);
    assert!((above - 0.01).abs() < 1e-4, "got {above}");
    // Below the pivot the evaluation frequency clamps to 50 MHz, so the
    // conductivity freezes at 0.1 * 0.05 = 0.005 S/m.
    let below = m.conductivity(10e6);
    assert!((below - 0.005).abs() < 1e-4, "got {below}");
}