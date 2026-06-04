//! Statistical sanity checks for the PRNG and Gaussian generator.

use rf_core::dsp::noise::{GaussianNoise, Xoshiro128PlusPlus};

#[test]
fn xoshiro_uniform_mean_near_half() {
    let mut rng = Xoshiro128PlusPlus::from_seed(42);
    let n = 200_000;
    let mean: f64 = (0..n).map(|_| rng.next_f32_unit() as f64).sum::<f64>() / n as f64;
    assert!((mean - 0.5).abs() < 0.005, "mean drifted: {mean}");
}

#[test]
fn gaussian_std_matches_sigma() {
    let mut g = GaussianNoise::new(7, 1.0);
    let n = 200_000;
    let mut sum = 0.0_f64;
    let mut sum2 = 0.0_f64;
    for _ in 0..n {
        let x = g.sample() as f64;
        sum += x;
        sum2 += x * x;
    }
    let mean = sum / n as f64;
    let var = sum2 / n as f64 - mean * mean;
    assert!((mean).abs() < 0.02, "mean too large: {mean}");
    assert!((var.sqrt() - 1.0).abs() < 0.02, "std too off: {}", var.sqrt());
}