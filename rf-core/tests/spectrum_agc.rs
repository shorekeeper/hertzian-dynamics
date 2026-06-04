//! AGC pulls envelope toward target.

use rf_core::spectrum::agc::{Agc, AgcConfig};
use rf_core::types::iq::Iq;

#[test]
fn agc_converges_on_constant_envelope() {
    let cfg = AgcConfig {
        target_amplitude: 0.5,
        attack_seconds: 0.001,
        release_seconds: 0.01,
        ..AgcConfig::default()
    };
    let mut agc = Agc::new(cfg, 48_000.0);
    let mut last_mag = 0.0_f32;
    for _ in 0..48_000 {
        let out = agc.process(Iq::new(2.0, 0.0));
        last_mag = out.magnitude();
    }
    assert!((last_mag - 0.5).abs() < 0.05, "final |y| = {last_mag}");
}

#[test]
fn agc_attack_is_faster_than_release() {
    let cfg = AgcConfig {
        target_amplitude: 0.5,
        attack_seconds: 0.001,
        release_seconds: 0.5,
        ..AgcConfig::default()
    };
    let mut agc = Agc::new(cfg, 48_000.0);
    // Slam in a loud step.
    for _ in 0..480 {
        let _ = agc.process(Iq::new(5.0, 0.0));
    }
    let attack_settled = agc.envelope();
    // Drop to a quiet level.
    for _ in 0..480 {
        let _ = agc.process(Iq::new(0.1, 0.0));
    }
    let after_quiet = agc.envelope();
    assert!(
        attack_settled > after_quiet,
        "release happened too fast: {attack_settled} -> {after_quiet}"
    );
    // And both diverged from the seed of 0.5.
    assert!(attack_settled > 0.7, "attack did not bring envelope up: {attack_settled}");
}