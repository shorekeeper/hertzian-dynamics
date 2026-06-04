//! Bullington and Fresnel parameter.

use rf_core::propagation::{
    bullington_equivalent_edge, deygout_loss_db, fresnel_parameter, knife_edge_loss_db,
};
use rf_core::propagation::knife_edge::TerrainSample;

#[test]
fn flat_terrain_has_no_equivalent_edge() {
    let profile = vec![
        TerrainSample { distance_m: 0.0, height_m: 0.0 },
        TerrainSample { distance_m: 500.0, height_m: 0.0 },
        TerrainSample { distance_m: 1000.0, height_m: 0.0 },
    ];
    let g = bullington_equivalent_edge(&profile, 5.0, 5.0);
    assert!(g.is_none() || g.unwrap().clearance_m <= 0.0);
}

#[test]
fn obstacle_above_line_of_sight_gives_positive_clearance() {
    let profile = vec![
        TerrainSample { distance_m: 0.0, height_m: 0.0 },
        TerrainSample { distance_m: 500.0, height_m: 30.0 },
        TerrainSample { distance_m: 1000.0, height_m: 0.0 },
    ];
    let g = bullington_equivalent_edge(&profile, 5.0, 5.0).expect("edge");
    assert!(g.clearance_m > 0.0, "clearance {}", g.clearance_m);
    assert!(g.d1_m > 0.0 && g.d2_m > 0.0);
    assert!((g.d1_m + g.d2_m - 1000.0).abs() < 1.0);
}

#[test]
fn knife_edge_loss_below_minus_point_seven_eight_is_zero() {
    assert_eq!(knife_edge_loss_db(-1.0), 0.0);
    assert_eq!(knife_edge_loss_db(-2.0), 0.0);
}

#[test]
fn knife_edge_loss_at_zero_is_about_six_db() {
    let l = knife_edge_loss_db(0.0);
    assert!((l - 6.02).abs() < 0.3, "got {l}");
}

#[test]
fn fresnel_parameter_zero_clearance_is_zero() {
    let v = fresnel_parameter(0.0, 500.0, 500.0, 3.0);
    assert_eq!(v, 0.0);
}

#[test]
fn fresnel_parameter_grows_with_clearance() {
    let v1 = fresnel_parameter(5.0, 500.0, 500.0, 3.0);
    let v2 = fresnel_parameter(15.0, 500.0, 500.0, 3.0);
    assert!(v2 > v1 + 0.5);
}

#[test]
fn deygout_two_ridges_exceeds_single_edge() {
    // Two comparable ridges. Deygout should report more loss than the
    // dominant edge alone, because the secondary ridge contributes a
    // term the single Bullington edge would have merged away.
    let profile = vec![
        TerrainSample { distance_m: 0.0, height_m: 0.0 },
        TerrainSample { distance_m: 300.0, height_m: 40.0 },
        TerrainSample { distance_m: 700.0, height_m: 38.0 },
        TerrainSample { distance_m: 1000.0, height_m: 0.0 },
    ];
    let total = deygout_loss_db(&profile, 5.0, 5.0, 100.0e6);
    let single = {
        let lambda = 299_792_458.0_f32 / 100.0e6_f32;
        let v = fresnel_parameter(40.0 - 5.0, 300.0, 700.0, lambda);
        knife_edge_loss_db(v)
    };
    assert!(total > single + 1.0,
        "deygout {total} should exceed single edge {single}");
}