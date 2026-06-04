//! FIR sanity: a windowed sinc low pass passes a sub cutoff tone
//! and stops a super cutoff tone.

use rf_core::dsp::fir::{design, Fir};
use rf_core::dsp::window::WindowKind;

#[test]
fn lowpass_passes_dc_and_blocks_nyquist() {
    let fs = 48_000.0_f32;
    let taps = design::lowpass(127, 4_000.0, fs, WindowKind::Blackman);
    let mut fir = Fir::new(taps);
    let n = 4096;
    let mut out = vec![0.0_f32; n];

    // DC sweep.
    let dc: Vec<f32> = vec![1.0; n];
    fir.process_block(&dc, &mut out);
    let dc_gain = out[n - 1];
    assert!((dc_gain - 1.0).abs() < 0.02, "DC gain {dc_gain}");

    // 20 kHz, well above cutoff.
    fir.reset();
    let hi: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * 20_000.0 * i as f32 / fs).sin())
        .collect();
    fir.process_block(&hi, &mut out);
    let peak = out[(n / 2)..].iter().fold(0.0_f32, |m, &v| m.max(v.abs()));
    assert!(peak < 0.05, "high band leakage {peak}");
}