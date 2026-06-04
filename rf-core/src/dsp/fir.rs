//! FIR filter with circular delay line.
//!
//! Convolution is the simplest filter form and the only one with
//! exactly linear phase (when taps are symmetric). We use it for
//! the Hilbert transformer, for the windowed sinc anti aliasing
//! filters, and for any band shaping where group delay matters.
//!
//! Storage is a circular buffer of length `taps.len()`. The naive
//! shift register form is avoided because it touches `O(N)` memory
//! per sample for no algorithmic gain.

use crate::types::iq::Iq;

/// FIR filter, single channel real input.
///
/// State carrying. Clone to fork an instance with independent
/// history (for example, one copy on I, one copy on Q).
#[derive(Clone, Debug)]
pub struct Fir {
    taps: Vec<f32>,
    delay: Vec<f32>,
    pos: usize,
}

impl Fir {
    /// Construct from precomputed coefficients. Tap zero is the
    /// most recent sample multiplier.
    pub fn new(taps: Vec<f32>) -> Self {
        let n = taps.len();
        assert!(n > 0, "FIR with zero taps");
        Self { taps, delay: vec![0.0; n], pos: 0 }
    }

    /// Reset delay line to zero. Does not change the coefficients.
    pub fn reset(&mut self) {
        for s in self.delay.iter_mut() {
            *s = 0.0;
        }
        self.pos = 0;
    }

    /// Number of coefficients. Equal to the filter order plus one.
    pub fn len(&self) -> usize {
        self.taps.len()
    }

    /// Borrow the coefficients, mainly for diagnostics.
    pub fn taps(&self) -> &[f32] {
        &self.taps
    }

    /// Process one sample. Reads `x`, writes the new sample into
    /// the circular buffer at `pos`, then walks the buffer to
    /// produce the output.
    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        let n = self.taps.len();
        self.delay[self.pos] = x;
        let mut acc = 0.0_f32;
        let mut idx = self.pos;
        for k in 0..n {
            acc += self.taps[k] * self.delay[idx];
            idx = if idx == 0 { n - 1 } else { idx - 1 };
        }
        self.pos = if self.pos + 1 == n { 0 } else { self.pos + 1 };
        acc
    }

    /// Block process, real to real. Sizes must match.
    pub fn process_block(&mut self, input: &[f32], output: &mut [f32]) {
        assert_eq!(input.len(), output.len(), "FIR block size mismatch");
        for (xi, yi) in input.iter().zip(output.iter_mut()) {
            *yi = self.process(*xi);
        }
    }
}

/// FIR design helpers. Functions return Vec<f32> of taps that can
/// be handed to `Fir::new`.
pub mod design {
    use core::f32::consts::PI;

    use crate::dsp::window::WindowKind;

    /// Windowed sinc low pass FIR. Cutoff `fc_hz` is the -6 dB point
    /// of the ideal response. Length `n` should be odd to keep a
    /// real centered group delay; the function does not enforce odd
    /// since some callers want explicit control.
    ///
    /// The DC gain is normalised to unity at the end so the caller
    /// does not have to.
    pub fn lowpass(n: usize, fc_hz: f32, fs_hz: f32, window: WindowKind) -> Vec<f32> {
        assert!(n > 0);
        assert!(fc_hz > 0.0 && fc_hz < fs_hz / 2.0);
        let m = (n - 1) as f32 / 2.0;
        let two_fc = 2.0 * fc_hz / fs_hz;
        let mut taps = Vec::with_capacity(n);
        for k in 0..n {
            let kf = k as f32 - m;
            let h = if kf.abs() < 1e-6 {
                two_fc
            } else {
                (PI * two_fc * kf).sin() / (PI * kf)
            };
            taps.push(h * window.coefficient(k, n));
        }
        let sum: f32 = taps.iter().sum();
        if sum.abs() > 0.0 {
            for t in taps.iter_mut() {
                *t /= sum;
            }
        }
        taps
    }

    /// Windowed sinc high pass FIR. Same conventions as `lowpass`.
    /// Implemented as spectral inversion of a lowpass at `fc`.
    pub fn highpass(n: usize, fc_hz: f32, fs_hz: f32, window: WindowKind) -> Vec<f32> {
        assert!(n & 1 == 1, "highpass length must be odd for true HP at Nyquist");
        let lp = lowpass(n, fc_hz, fs_hz, window);
        let m = (n - 1) / 2;
        let mut hp = lp;
        for (k, t) in hp.iter_mut().enumerate() {
            *t = -*t;
            if k == m {
                *t += 1.0;
            }
        }
        hp
    }
}