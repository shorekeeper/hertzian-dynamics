//! Generate Gaussian noise, FFT, verify the spectrum is roughly flat.
//!
//! Run: cargo run --example noise_floor

use rf_core::dsp::fft::{fft, FftDirection};
use rf_core::dsp::noise::GaussianNoise;
use rf_core::dsp::window::WindowKind;
use rf_core::io::{csv, example_output_dir};
use rf_core::types::iq::Iq;

const N: usize = 4096;

fn main() {
    let mut noise = GaussianNoise::new(0xDEAD_BEEF, 1.0);
    let mut data = vec![Iq::ZERO; N];
    noise.fill_iq(&mut data);

    let win = WindowKind::Hann.make(N);
    for (i, s) in data.iter_mut().enumerate() {
        *s = s.scale(win[i]);
    }

    fft(&mut data, FftDirection::Forward).expect("FFT");

    let spectrum: Vec<(f32, f32)> = (0..N)
        .map(|k| {
            let bin = k as f32;
            let mag_db = 20.0 * (data[k].magnitude().max(1e-12)).log10();
            (bin, mag_db)
        })
        .collect();

    let dir = example_output_dir("noise");
    csv::write_pair_csv(dir.join("spectrum.csv"), "bin", "mag_db", &spectrum).unwrap();

    let mean: f32 = spectrum.iter().map(|p| p.1).sum::<f32>() / N as f32;
    let var: f32 = spectrum.iter().map(|p| (p.1 - mean).powi(2)).sum::<f32>() / N as f32;
    println!("Noise floor mean = {mean:.1} dB, std = {:.1} dB", var.sqrt());
    println!("Output: {}", dir.join("spectrum.csv").display());
}