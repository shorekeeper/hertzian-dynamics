//! Friis sanity. Known values from textbook references.

use rf_core::propagation::{free_space_loss_db, free_space_loss_linear, wavelength_m};

#[test]
fn wavelength_at_one_megahertz_is_about_300m() {
    let w = wavelength_m(1.0e6);
    assert!((w - 299.792458).abs() < 1e-3, "wavelength was {w}");
}

#[test]
fn fspl_1km_100mhz_matches_textbook() {
    // 100 MHz, 1 km. lambda = 2.998 m. FSPL = 20 log10(4 pi 1000 / 2.998)
    //                                       ~ 20 log10(4191) ~ 72.45 dB
    let l = free_space_loss_db(1000.0, 100.0e6);
    assert!((l - 72.45).abs() < 0.1, "FSPL was {l}");
}

#[test]
fn fspl_doubling_distance_adds_six_db() {
    let l1 = free_space_loss_db(100.0, 100.0e6);
    let l2 = free_space_loss_db(200.0, 100.0e6);
    assert!((l2 - l1 - 6.02).abs() < 0.05);
}

#[test]
fn linear_form_matches_db_form() {
    let d = 500.0_f32;
    let f = 50.0e6_f64;
    let db = free_space_loss_db(d, f);
    let linear = free_space_loss_linear(d, f);
    let from_db = 10.0_f32.powf(-db / 10.0);
    assert!((from_db - linear).abs() / linear < 1e-3);
}