//! Window functions.
//!
//! Used in two places:
//!   1. Pre multiplying a block of samples before an FFT, so the
//!      implicit periodic extension does not show up as sidelobes.
//!   2. Tapering the impulse response of a truncated ideal filter
//!      (windowed sinc, windowed Hilbert) to reduce passband ripple.
//!
//! All windows here are symmetric with `N` samples, indexed `0..N`.
//! The classical periodic vs symmetric distinction matters for
//! perfect reconstruction overlap add; for our use cases the
//! symmetric form is what we want.

use core::f32::consts::PI;

/// Window family selector.
///
/// Numbers map to the rough sidelobe attenuation in decibels and
/// match the order of mainlobe width: Hann is narrowest, Blackman
/// widest. We expose the kind rather than an enum of precomputed
/// vectors so the caller decides where to store the coefficients.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum WindowKind {
    /// Rectangular, no taper. -13 dB sidelobes.
    Rectangular,
    /// Hann, the `cos^2` window. -31 dB sidelobes.
    Hann,
    /// Hamming, raised cosine with offset. -42 dB sidelobes.
    Hamming,
    /// Blackman, three term cosine. -58 dB sidelobes.
    Blackman,
}

impl WindowKind {
    /// Coefficient at index `n` of a length `total` window.
    /// `total` must be at least 2. Returns `1.0` for the
    /// rectangular case in constant time.
    pub fn coefficient(self, n: usize, total: usize) -> f32 {
        debug_assert!(total >= 2);
        debug_assert!(n < total);
        let denom = (total - 1) as f32;
        let x = 2.0 * PI * n as f32 / denom;
        match self {
            Self::Rectangular => 1.0,
            Self::Hann => 0.5 * (1.0 - x.cos()),
            Self::Hamming => 0.54 - 0.46 * x.cos(),
            Self::Blackman => 0.42 - 0.5 * x.cos() + 0.08 * (2.0 * x).cos(),
        }
    }

    /// Precompute the entire window into a fresh Vec. Use when the
    /// same window is applied many times to blocks of the same size.
    pub fn make(self, total: usize) -> Vec<f32> {
        (0..total).map(|n| self.coefficient(n, total)).collect()
    }
}

/// In place application of a precomputed window. Slice lengths must
/// match; panics otherwise.
pub fn apply_in_place(samples: &mut [f32], window: &[f32]) {
    assert_eq!(samples.len(), window.len(), "window length mismatch");
    for (s, &w) in samples.iter_mut().zip(window.iter()) {
        *s *= w;
    }
}