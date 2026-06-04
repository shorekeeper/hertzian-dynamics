//! FFT correctness against analytic single bin signals.

use rf_core::dsp::fft::{fft, ifft, FftDirection};
use rf_core::types::iq::Iq;

#[test]
fn impulse_yields_flat_spectrum() {
    let n = 64;
    let mut data = vec![Iq::ZERO; n];
    data[0] = Iq::new(1.0, 0.0);
    fft(&mut data, FftDirection::Forward).unwrap();
    for s in &data {
        assert!((s.i - 1.0).abs() < 1e-5, "expected 1.0, got {}", s.i);
        assert!(s.q.abs() < 1e-5);
    }
}

#[test]
fn single_tone_peaks_in_one_bin() {
    let n = 256;
    let k0 = 7;
    let mut data: Vec<Iq> = (0..n)
        .map(|i| {
            let angle = core::f32::consts::TAU * k0 as f32 * i as f32 / n as f32;
            Iq::cis(angle)
        })
        .collect();
    fft(&mut data, FftDirection::Forward).unwrap();
    let mut best = 0;
    for k in 0..n {
        if data[k].magnitude() > data[best].magnitude() {
            best = k;
        }
    }
    assert_eq!(best, k0);
    assert!(data[k0].magnitude() > (n as f32 * 0.95));
}

#[test]
fn forward_then_inverse_recovers_input() {
    let n = 128;
    let mut data: Vec<Iq> = (0..n)
        .map(|i| Iq::new((i as f32 * 0.137).sin(), (i as f32 * 0.241).cos()))
        .collect();
    let original = data.clone();
    fft(&mut data, FftDirection::Forward).unwrap();
    ifft(&mut data).unwrap();
    for (a, b) in data.iter().zip(original.iter()) {
        assert!((a.i - b.i).abs() < 1e-4, "{} vs {}", a.i, b.i);
        assert!((a.q - b.q).abs() < 1e-4, "{} vs {}", a.q, b.q);
    }
}

#[test]
fn non_power_of_two_is_rejected() {
    let mut data = vec![Iq::ZERO; 7];
    assert!(fft(&mut data, FftDirection::Forward).is_err());
}