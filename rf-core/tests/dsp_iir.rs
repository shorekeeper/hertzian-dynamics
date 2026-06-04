//! Butterworth LP: pass band ~0 dB, stop band heavily attenuated.

use rf_core::dsp::iir::ButterworthLp;

fn measure_gain_db(fs: f32, fc: f32, order: usize, test_hz: f32) -> f32 {
    let n = (fs * 0.5) as usize;
    let mut lp = ButterworthLp::new(order, fs, fc);
    let mut out = vec![0.0_f32; n];
    let input: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * test_hz * i as f32 / fs).sin())
        .collect();
    lp.process_block(&input, &mut out);
    let skip = n / 4;
    let peak = out[skip..].iter().fold(0.0_f32, |m, &v| m.max(v.abs()));
    20.0 * peak.max(1e-9).log10()
}

#[test]
fn passband_is_near_zero_db() {
    let g = measure_gain_db(48_000.0, 5_000.0, 4, 500.0);
    assert!(g > -0.5 && g < 0.5, "passband gain {} dB out of range", g);
}

#[test]
fn stopband_is_heavily_attenuated() {
    let g = measure_gain_db(48_000.0, 5_000.0, 4, 15_000.0);
    assert!(g < -40.0, "stopband gain {} dB not low enough", g);
}