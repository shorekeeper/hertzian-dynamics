//! GPU FFT against the CPU reference. Gated by gpu_tests because the
//! host needs a working Vulkan device.

#![cfg(feature = "gpu_tests")]

use rf_core::dsp::{fft, fft_gpu::GpuFft, FftDirection};
use rf_core::types::iq::Iq;
use rf_core::{RfCore, RfCoreConfig};

fn make_input(n: usize, seed: u64) -> Vec<Iq> {
    // Deterministic mixture: two tones plus a touch of noise. Phase
    // chosen so the result has non trivial both rails.
    let mut rng = rf_core::dsp::Xoshiro128PlusPlus::from_seed(seed);
    (0..n)
        .map(|i| {
            let t = i as f32;
            let a = 0.5 * (core::f32::consts::TAU * 7.0 * t / n as f32).cos();
            let b = 0.3 * (core::f32::consts::TAU * 23.0 * t / n as f32).sin();
            let nz = rng.next_f32_unit() * 0.02 - 0.01;
            Iq::new(a + nz, b + nz * 0.5)
        })
        .collect()
}

fn max_abs_err(a: &[Iq], b: &[Iq]) -> f32 {
    a.iter()
        .zip(b.iter())
        .map(|(x, y)| ((x.i - y.i).powi(2) + (x.q - y.q).powi(2)).sqrt())
        .fold(0.0_f32, f32::max)
}

#[test]
fn gpu_matches_cpu_forward_n_1024() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, 4096).expect("init GpuFft");

    let mut cpu = make_input(1024, 1);
    let mut gpu_buf = cpu.clone();

    fft(&mut cpu, FftDirection::Forward).expect("cpu fft");
    gpu.execute(&mut gpu_buf, FftDirection::Forward).expect("gpu fft");

    let err = max_abs_err(&cpu, &gpu_buf);
    assert!(err < 5e-4, "max abs error {err} too large");
}

#[test]
fn gpu_matches_cpu_inverse_n_2048() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, 4096).expect("init GpuFft");

    let mut cpu = make_input(2048, 7);
    let mut gpu_buf = cpu.clone();

    fft(&mut cpu, FftDirection::Inverse).expect("cpu fft");
    gpu.execute(&mut gpu_buf, FftDirection::Inverse).expect("gpu fft");

    let err = max_abs_err(&cpu, &gpu_buf);
    assert!(err < 5e-4, "max abs error {err} too large");
}

#[test]
fn gpu_roundtrip_recovers_input_n_4096() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, 4096).expect("init GpuFft");

    let original = make_input(4096, 13);
    let mut buf = original.clone();

    gpu.execute(&mut buf, FftDirection::Forward).expect("forward");
    gpu.execute(&mut buf, FftDirection::Inverse).expect("inverse");

    let err = max_abs_err(&original, &buf);
    assert!(err < 1e-3, "round trip max abs error {err}");
}

#[test]
fn rejects_non_power_of_two() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, 4096).expect("init GpuFft");
    let mut buf = vec![Iq::ZERO; 1000];
    assert!(gpu.execute(&mut buf, FftDirection::Forward).is_err());
}

#[test]
fn rejects_undersize() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, 4096).expect("init GpuFft");
    let mut buf = vec![Iq::ZERO; 256];
    assert!(gpu.execute(&mut buf, FftDirection::Forward).is_err());
}