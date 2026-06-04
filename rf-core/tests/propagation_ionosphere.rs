//! Ionosphere LUT and MUF behaviour.

use rf_core::propagation::{IonosphereLut, SolarActivity};

#[test]
fn night_fo_f2_is_lower_than_day() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    let day = l.fo_f2_at_hour(12.0);
    let night = l.fo_f2_at_hour(3.0);
    assert!(day > night, "day {day} not > night {night}");
}

#[test]
fn high_activity_has_higher_muf_than_low() {
    let low = IonosphereLut::for_activity(SolarActivity::Low);
    let high = IonosphereLut::for_activity(SolarActivity::High);
    let s_low = low.evaluate(10.0e6, 2_000_000.0, 12.0);
    let s_high = high.evaluate(10.0e6, 2_000_000.0, 12.0);
    assert!(s_high.muf_hz > s_low.muf_hz);
}

#[test]
fn above_muf_path_is_infinite() {
    let l = IonosphereLut::for_activity(SolarActivity::Low);
    let s = l.evaluate(30.0e6, 100_000.0, 12.0);
    assert!(!s.absorption_db.is_finite() || s.absorption_db > 1.0e6);
}

#[test]
fn hop_count_scales_with_distance() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    let s_short = l.evaluate(10.0e6, 1_000_000.0, 12.0);
    let s_long = l.evaluate(10.0e6, 8_000_000.0, 12.0);
    assert!(s_long.hops >= s_short.hops);
}

#[test]
fn virtual_path_length_exceeds_ground_distance() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    let virt = l.virtual_path_length_m(2_000_000.0, 1);
    assert!(virt > 2_000_000.0);
}

#[test]
fn daytime_absorption_falls_with_frequency() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    // 3000 km path keeps both frequencies below the noon MUF.
    let low = l.evaluate(7.0e6, 3_000_000.0, 12.0);
    let high = l.evaluate(21.0e6, 3_000_000.0, 12.0);
    assert!(low.absorption_db.is_finite() && high.absorption_db.is_finite());
    // Inverse-square law: 7 MHz should be far lossier than 21 MHz.
    assert!(low.absorption_db > high.absorption_db * 4.0,
        "7 MHz {} should dwarf 21 MHz {}", low.absorption_db, high.absorption_db);
}

#[test]
fn daytime_absorption_exceeds_night() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    let day = l.evaluate(7.0e6, 3_000_000.0, 12.0);
    let night = l.evaluate(7.0e6, 3_000_000.0, 0.0);
    assert!(day.absorption_db > night.absorption_db,
        "day {} should exceed night {}", day.absorption_db, night.absorption_db);
}

#[test]
fn absorption_peaks_near_noon() {
    let l = IonosphereLut::for_activity(SolarActivity::Medium);
    let noon = l.evaluate(10.0e6, 3_000_000.0, 12.0);
    let morning = l.evaluate(10.0e6, 3_000_000.0, 9.0);
    // The noon sun sits higher than mid morning, so the D-layer is more
    // ionized and absorbs more.
    assert!(noon.absorption_db >= morning.absorption_db,
        "noon {} should be at least mid-morning {}", noon.absorption_db, morning.absorption_db);
}