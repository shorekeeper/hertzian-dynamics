//! Digital signal processing primitives.
//!
//! Every submodule owns one well isolated algorithm. The split keeps
//! unit tests small and lets a future GPU port replace exactly one
//! module without touching the rest.
//!
//! Conventions
//! -----------
//!
//! * Samples are `f32` for real signals and `Iq` for complex
//!   baseband. The choice is documented per function.
//! * Sample rate is always passed in hertz as `f32`. Internal math
//!   that requires more than ~7 decimal digits of headroom (for
//!   example phase accumulators that run for minutes) is documented
//!   to use `f64` locally.
//! * All processing functions operate on caller provided slices.
//!   Buffers are never allocated on the hot path.
//! * State carrying processors expose `reset()` so callers can
//!   restart them without reconstructing.

pub mod window;
pub mod osc;
pub mod dc_block;
pub mod noise;
pub mod fir;
pub mod iir;
pub mod hilbert;
pub mod fft;
pub mod fft_gpu;
pub mod zoom_dft;
pub mod zoom_dft_gpu;
pub mod modulation;

pub use dc_block::DcBlocker;
pub use fft::{fft, ifft, FftDirection};
pub use fft_gpu::{GpuFft, GPU_FFT_MAX_N, GPU_FFT_MIN_N};
pub use fir::Fir;
pub use hilbert::HilbertFir;
pub use iir::{Biquad, ButterworthLp};
pub use noise::{GaussianNoise, Xoshiro128PlusPlus};
pub use osc::Nco;
pub use window::WindowKind;
pub use zoom_dft::{bin_sums_cpu, finalize_db, hann_window};
pub use zoom_dft_gpu::GpuZoomDft;