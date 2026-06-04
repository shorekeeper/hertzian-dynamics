//! AM modulate, optional noise, AM demodulate. Dumps audio in and
//! out, plus a baseband IQ trace.
//!
//! Run: cargo run --example am_roundtrip
//! Outputs: target/hertzian_out/am/{input,output}.wav and baseband.csv

use rf_core::dsp::modulation::{AmDemodulator, AmModulator, AudioDemodulator, AudioModulator};
use rf_core::dsp::noise::GaussianNoise;
use rf_core::io::{csv, example_output_dir, wav};
use rf_core::types::iq::Iq;

const FS: f32 = 48_000.0;
const TONE_HZ: f32 = 1_000.0;
const SECONDS: f32 = 1.0;
const MOD_INDEX: f32 = 0.8;
const NOISE_SIGMA: f32 = 0.02;

fn main() {
    let n = (FS * SECONDS) as usize;
    let mut audio: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * TONE_HZ * i as f32 / FS).sin() * 0.8)
        .collect();

    let mut baseband = vec![Iq::ZERO; n];
    let mut modulator = AmModulator::new(MOD_INDEX);
    modulator.modulate(&audio, &mut baseband);

    if NOISE_SIGMA > 0.0 {
        let mut noise = GaussianNoise::new(0xC0FFEE, NOISE_SIGMA);
        let mut noise_buf = vec![Iq::ZERO; n];
        noise.fill_iq(&mut noise_buf);
        for (b, nv) in baseband.iter_mut().zip(noise_buf.iter()) {
            *b = b.add(*nv);
        }
    }

    let mut recovered = vec![0.0_f32; n];
    let mut demodulator = AmDemodulator::new(MOD_INDEX);
    demodulator.demodulate(&baseband, &mut recovered);

    let dir = example_output_dir("am");
    wav::write_mono_pcm16(dir.join("input.wav"), &audio, FS as u32).unwrap();
    wav::write_mono_pcm16(dir.join("output.wav"), &recovered, FS as u32).unwrap();
    let pairs: Vec<(f32, f32)> = baseband.iter().take(2048).map(|s| (s.i, s.q)).collect();
    csv::write_pair_csv(dir.join("baseband.csv"), "i", "q", &pairs).unwrap();

    let skip = (FS * 0.1) as usize;
    let snr = snr_db(&audio[skip..], &recovered[skip..]);
    println!("AM round trip: SNR = {snr:.1} dB");
    println!("Outputs: {}", dir.display());

    audio.clear();
    assert!(snr > 20.0, "AM SNR too low: {snr}");
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