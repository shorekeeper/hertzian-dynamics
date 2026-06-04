//! Zoom DFT CPU reference and shared post-processing.
//!
//! The zoom DFT projects a fixed number of frequency bins across an
//! arbitrary analysis span centered on baseband DC. Unlike a plain FFT
//! the bin spacing is span/bins rather than fs/n, so the analyzer can
//! zoom into a narrow window of the receiver passband at full bin
//! density. Each bin is a complex sum over the windowed samples computed
//! with a per-sample phase recurrence.
//!
//! This module holds three pieces shared by the CPU and GPU backends:
//!
//!   * `hann_window` builds the Hann window applied before the DFT.
//!   * `bin_sums_cpu` is the CPU reference for the per-bin complex sums.
//!     The GPU shader in shaders/zoom_dft.comp runs the same arithmetic
//!     in the same accumulation order.
//!   * `finalize_db` converts the raw complex bin sums into per-bin
//!     magnitude in dB. It applies the power spectral density
//!     normalisation derived from the window and the center-bin
//!     interpolation used at wide spans. Both backends share it, so the
//!     only numerical difference between them is the f32 rounding of the
//!     bin sums.
//!
//! Numerical note: the phase recurrence runs in f32 on both backends so
//! they match. The window sums and the dB conversion run in f64 for
//! stability; they are O(n) and O(bins) and do not dominate.

use core::f32::consts::TAU;

use crate::types::iq::Iq;

/// Symmetric Hann window of length n. For n below two the window
/// degenerates to all ones, which keeps the caller from dividing by zero
/// in the normalisation.
pub fn hann_window(n: usize) -> Vec<f32> {
    if n < 2 {
        return vec![1.0; n.max(1)];
    }
    let denom = (n - 1) as f32;
    (0..n)
        .map(|k| 0.5 - 0.5 * (TAU * k as f32 / denom).cos())
        .collect()
}

/// Compute the per-bin complex sums on the CPU. `iq` holds at least `n`
/// complex samples, `hann` is the length-n window, and `out` receives
/// `bins` complex sums. The window is applied inline so the caller does
/// not need a pre-windowed copy.
pub fn bin_sums_cpu(
    iq: &[Iq],
    hann: &[f32],
    n: usize,
    span_hz: f32,
    fs_hz: f32,
    bins: usize,
    out: &mut [Iq],
) {
    debug_assert!(iq.len() >= n);
    debug_assert!(hann.len() >= n);
    debug_assert!(out.len() >= bins);

    let half_span = span_hz * 0.5;
    let bin_width = span_hz / bins as f32;

    for b in 0..bins {
        let offset_hz = -half_span + (b as f32 + 0.5) * bin_width;
        let phase_inc = -TAU * offset_hz / fs_hz;
        let cos_inc = phase_inc.cos();
        let sin_inc = phase_inc.sin();
        let mut c = 1.0_f32;
        let mut s = 0.0_f32;
        let mut sum_i = 0.0_f32;
        let mut sum_q = 0.0_f32;
        for k in 0..n {
            let wi = iq[k].i * hann[k];
            let wq = iq[k].q * hann[k];
            sum_i += wi * c - wq * s;
            sum_q += wi * s + wq * c;
            let nc = c * cos_inc - s * sin_inc;
            let ns = c * sin_inc + s * cos_inc;
            c = nc;
            s = ns;
        }
        out[b] = Iq::new(sum_i, sum_q);
    }
}

/// Apply window-derived PSD normalisation and convert the complex bin
/// sums to per-bin magnitude in dB. `out_db` receives `bins` values.
///
/// At spans of 10 kHz and above the center bin is replaced with the
/// average of its two neighbours. The receiver baseband often carries a
/// strong DC residual from AGC and the mixer, which lands in the center
/// bin and reads as a spurious carrier; the interpolation suppresses it
/// without affecting real signals offset from center.
pub fn finalize_db(
    sums: &[Iq],
    n: usize,
    hann: &[f32],
    span_hz: f32,
    bins: usize,
    out_db: &mut [f32],
) {
    debug_assert!(sums.len() >= bins);
    debug_assert!(hann.len() >= n);
    debug_assert!(out_db.len() >= bins);

    let mut sum_w = 0.0_f64;
    let mut sum_w2 = 0.0_f64;
    for &w in hann.iter().take(n) {
        let wd = w as f64;
        sum_w += wd;
        sum_w2 += wd * wd;
    }
    let coherent_gain_sq = (sum_w * sum_w).max(1e-30);
    let enbw = (n as f64) * sum_w2 / coherent_gain_sq;
    let psd_norm = 1.0 / (coherent_gain_sq * enbw).max(1e-30);

    for b in 0..bins {
        let re = sums[b].i as f64;
        let im = sums[b].q as f64;
        let power = (re * re + im * im) * psd_norm;
        out_db[b] = (10.0 * power.max(1e-15).log10()) as f32;
    }

    if span_hz >= 10_000.0 && bins > 2 {
        let center = bins / 2;
        out_db[center] = 0.5 * (out_db[center - 1] + out_db[center + 1]);
    }
}