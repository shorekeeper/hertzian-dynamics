//! GPU FFT cross-check against the CPU reference. Run on a host with
//! a working Vulkan device and a build that has built the SPIR-V.
//!
//! Run: cargo run --example gpu_fft_validate
//!
//! What to look for:
//!   * `max abs error` printed on stdout should be below ~5e-4.
//!     Anything above 1e-3 hints at either a shader bug, wrong push
//!     constants or a memory layout mismatch.
//!   * The CSV files dropped under target/hertzian_out/gpu_fft/
//!     contain the CPU spectrum, the GPU spectrum, and the per-bin
//!     error so a plot can show where the deviation sits.
//!   * Round trip error should be similar in magnitude; a sharply
//!     larger value means the inverse path is misconfigured.

use rf_core::dsp::{fft, fft_gpu::GpuFft, FftDirection};
use rf_core::io::{csv, example_output_dir};
use rf_core::types::iq::Iq;
use rf_core::{RfCore, RfCoreConfig};

const N: usize = 2048;

fn main() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    let mut gpu = GpuFft::new(&core, N).expect("init GpuFft");

    let info = core.device().properties();
    let name_ptr = info.device_name.as_ptr();
    // SAFETY: device_name is a fixed size NUL terminated array
    // populated by the driver.
    let name = unsafe { std::ffi::CStr::from_ptr(name_ptr) }.to_string_lossy();
    println!("device: {name}");

    let input: Vec<Iq> = (0..N)
        .map(|i| {
            let t = i as f32 / N as f32;
            let a = (core::f32::consts::TAU * 17.0 * t).cos() * 0.7;
            let b = (core::f32::consts::TAU * 64.0 * t).sin() * 0.4;
            Iq::new(a, b)
        })
        .collect();

    let mut cpu = input.clone();
    let mut on_gpu = input.clone();

    fft(&mut cpu, FftDirection::Forward).expect("cpu");
    gpu.execute(&mut on_gpu, FftDirection::Forward).expect("gpu");

    let mut err_max = 0.0_f32;
    let mut spectrum_cpu = Vec::with_capacity(N);
    let mut spectrum_gpu = Vec::with_capacity(N);
    let mut spectrum_err = Vec::with_capacity(N);
    for k in 0..N {
        let mc = cpu[k].magnitude();
        let mg = on_gpu[k].magnitude();
        let de = ((cpu[k].i - on_gpu[k].i).powi(2) + (cpu[k].q - on_gpu[k].q).powi(2)).sqrt();
        err_max = err_max.max(de);
        spectrum_cpu.push((k as f32, mc));
        spectrum_gpu.push((k as f32, mg));
        spectrum_err.push((k as f32, de));
    }

    let dir = example_output_dir("gpu_fft");
    csv::write_pair_csv(dir.join("cpu_mag.csv"), "bin", "mag", &spectrum_cpu).unwrap();
    csv::write_pair_csv(dir.join("gpu_mag.csv"), "bin", "mag", &spectrum_gpu).unwrap();
    csv::write_pair_csv(dir.join("err_per_bin.csv"), "bin", "abs_err", &spectrum_err).unwrap();

    println!("max abs error (forward, N = {N}): {err_max:.3e}");
    println!("dumps: {}", dir.display());

    let mut buf = input.clone();
    gpu.execute(&mut buf, FftDirection::Forward).expect("fwd");
    gpu.execute(&mut buf, FftDirection::Inverse).expect("inv");
    let rt_err = input
        .iter()
        .zip(buf.iter())
        .map(|(a, b)| ((a.i - b.i).powi(2) + (a.q - b.q).powi(2)).sqrt())
        .fold(0.0_f32, f32::max);
    println!("max abs error (round trip): {rt_err:.3e}");

    assert!(err_max < 5e-4, "forward error too large: {err_max}");
    assert!(rt_err < 1e-3, "round trip error too large: {rt_err}");
}