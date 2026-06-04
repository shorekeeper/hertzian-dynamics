//! FFT of a synthetic two tone signal; dump magnitude spectrum.
//!
//! Run: cargo run --example fft_spectrum

use rf_core::dsp::fft::{fft, FftDirection};
use rf_core::dsp::window::WindowKind;
use rf_core::io::{csv, example_output_dir};
use rf_core::types::iq::Iq;

const FS: f32 = 48_000.0;
const N: usize = 4096;

fn main() {
    let win = WindowKind::Blackman.make(N);
    let mut data: Vec<Iq> = (0..N)
        .map(|i| {
            let t = i as f32 / FS;
            let s = (2.0 * std::f32::consts::PI * 1_000.0 * t).sin() * 0.5
                + (2.0 * std::f32::consts::PI * 3_500.0 * t).sin() * 0.25;
            Iq { i: s * win[i], q: 0.0 }
        })
        .collect();

    fft(&mut data, FftDirection::Forward).expect("FFT");

    let mut spectrum = Vec::with_capacity(N);
    for k in 0..N {
        let freq_hz = if k < N / 2 {
            k as f32 * FS / N as f32
        } else {
            (k as f32 - N as f32) * FS / N as f32
        };
        let mag_db = 20.0 * (data[k].magnitude().max(1e-12)).log10();
        spectrum.push((freq_hz, mag_db));
    }

    let dir = example_output_dir("fft");
    csv::write_pair_csv(dir.join("spectrum.csv"), "freq_hz", "mag_db", &spectrum).unwrap();

    let mut bins: Vec<(usize, f32)> = (0..N / 2)
        .map(|k| (k, 20.0 * (data[k].magnitude().max(1e-12)).log10()))
        .collect();
    bins.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap());
    println!("Top bin: {} Hz, {:.1} dB", bins[0].0 as f32 * FS / N as f32, bins[0].1);
    println!("Second:  {} Hz, {:.1} dB", bins[1].0 as f32 * FS / N as f32, bins[1].1);
    println!("Output: {}", dir.join("spectrum.csv").display());
}