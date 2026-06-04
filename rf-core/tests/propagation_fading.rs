//! Rayleigh statistics. Mean power should sit near unity with the
//! default sigma; long-run amplitude follows Rayleigh.

use rf_core::propagation::RayleighFading;

#[test]
fn mean_power_is_near_unity() {
    let mut f = RayleighFading::new(0xBEEF, 0.0);
    let n = 50_000;
    let mut sum = 0.0_f64;
    for _ in 0..n {
        let r = f.sample();
        sum += (r * r) as f64;
    }
    let mean = sum / n as f64;
    assert!((mean - 1.0).abs() < 0.1, "mean power {mean}");
}

#[test]
fn coherent_fade_is_slower_than_uncorrelated() {
    let mut uncorr = RayleighFading::new(1, 0.0);
    let mut corr = RayleighFading::new(1, 0.95);
    let n = 5_000;
    let mut var_u = 0.0_f64;
    let mut var_c = 0.0_f64;
    let mut prev_u = uncorr.sample();
    let mut prev_c = corr.sample();
    for _ in 0..n {
        let u = uncorr.sample();
        let c = corr.sample();
        var_u += ((u - prev_u) as f64).powi(2);
        var_c += ((c - prev_c) as f64).powi(2);
        prev_u = u;
        prev_c = c;
    }
    assert!(var_c < var_u, "var_c {var_c} should be less than var_u {var_u}");
}