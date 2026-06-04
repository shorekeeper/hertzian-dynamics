//! FM modulate, FM demodulate, dump.
//!
//! Run: cargo run --example fm_roundtrip

use rf_core::dsp::modulation::{AudioDemodulator, AudioModulator, FmDemodulator, FmModulator};
use rf_core::io::{csv, example_output_dir, wav};
use rf_core::types::iq::Iq;

const FS: f32 = 48_000.0;
const TONE_HZ: f32 = 1_000.0;
const SECONDS: f32 = 1.0;
const DEVIATION_HZ: f32 = 5_000.0;

fn main() {
    let n = (FS * SECONDS) as usize;
    let audio: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * TONE_HZ * i as f32 / FS).sin() * 0.8)
        .collect();

    let mut baseband = vec![Iq::ZERO; n];
    let mut modulator = FmModulator::new(DEVIATION_HZ, FS);
    modulator.modulate(&audio, &mut baseband);

    let mut recovered = vec![0.0_f32; n];
    let mut demodulator = FmDemodulator::new(DEVIATION_HZ, FS);
    demodulator.demodulate(&baseband, &mut recovered);

    let dir = example_output_dir("fm");
    wav::write_mono_pcm16(dir.join("input.wav"), &audio, FS as u32).unwrap();
    wav::write_mono_pcm16(dir.join("output.wav"), &recovered, FS as u32).unwrap();
    let pairs: Vec<(f32, f32)> = baseband.iter().take(2048).map(|s| (s.i, s.q)).collect();
    csv::write_pair_csv(dir.join("baseband.csv"), "i", "q", &pairs).unwrap();

    let skip = (FS * 0.05) as usize;
    let snr = snr_db(&audio[skip..], &recovered[skip..]);
    println!("FM round trip: SNR = {snr:.1} dB");
    println!("Outputs: {}", dir.display());

    assert!(snr > 30.0, "FM SNR too low: {snr}");
}

fn snr_db(reference: &[f32], measured: &[f32]) -> f32 {
    let n = reference.len().min(measured.len());
    let mut sig = 0.0_f64;
    let mut err = 0.0_f64;
    for i in 0..n {
        let r = reference[i] as f64;
        let m = measured[i] as f64;
        sig += r * r;
        err += (m - r) * (m - r);
    }
    if err <= 0.0 {
        return 200.0;
    }
    10.0 * (sig / err).log10() as f32
}