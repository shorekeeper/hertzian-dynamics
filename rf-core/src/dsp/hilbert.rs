//! Hilbert transformer as a FIR filter.
//!
//! The ideal Hilbert impulse response is `h[n] = 2 / (pi n)` for odd
//! `n`, zero for even `n`, infinite length. We truncate to `N` taps
//! centered at zero and apply a window to keep ripple under control.
//! Length must be odd so the center tap aligns with zero where the
//! ideal response is also zero. Sidelobe attenuation is the choice
//! between speed (short N) and quality (long N); 65 to 127 covers
//! voice grade SSB well.
//!
//! Group delay is `(N - 1) / 2` samples; callers that need phase
//! alignment must delay the in phase rail by the same amount.

use core::f32::consts::PI;

use crate::dsp::fir::Fir;
use crate::dsp::window::WindowKind;

/// Wrapper around a `Fir` configured as a Hilbert transformer plus a
/// matched delay line for the in phase rail. Use `process_iq` to
/// build an analytic signal from a real input in one call.
#[derive(Clone, Debug)]
pub struct HilbertFir {
    h: Fir,
    delay: Vec<f32>,
    delay_pos: usize,
    half_len: usize,
}

impl HilbertFir {
    /// Construct an `n` tap Hilbert FIR with the chosen window.
    /// Panics if `n` is even.
    pub fn new(n: usize, window: WindowKind) -> Self {
        assert!(n & 1 == 1, "Hilbert FIR length must be odd");
        let m = (n as i32 - 1) / 2;
        let mut taps = Vec::with_capacity(n);
        for k in 0..n {
            let kf = k as i32 - m;
            let ideal = if kf == 0 || (kf & 1) == 0 {
                0.0
            } else {
                2.0 / (PI * kf as f32)
            };
            taps.push(ideal * window.coefficient(k, n));
        }
        let half_len = (n - 1) / 2;
        Self {
            h: Fir::new(taps),
            delay: vec![0.0; half_len + 1],
            delay_pos: 0,
            half_len,
        }
    }

    /// Reset internal state.
    pub fn reset(&mut self) {
        self.h.reset();
        for s in self.delay.iter_mut() {
            *s = 0.0;
        }
        self.delay_pos = 0;
    }

    /// Group delay introduced by the filter, in samples.
    pub fn group_delay_samples(&self) -> usize {
        self.half_len
    }

    /// Process a real sample, return the analytic signal sample
    /// `(x_delayed, hilbert(x))`. The in phase part is the input
    /// delayed by `half_len` samples to align with the Hilbert FIR
    /// output, which has the same group delay.
    #[inline]
    pub fn process(&mut self, x: f32) -> (f32, f32) {
        let imag = self.h.process(x);
        let n = self.delay.len();
        // Circular buffer of length half_len + 1. Write the new
        // sample at delay_pos, advance, then read at the new
        // delay_pos: that slot holds the oldest sample (written
        // half_len calls ago), giving the exact matching delay.
        self.delay[self.delay_pos] = x;
        self.delay_pos = if self.delay_pos + 1 == n { 0 } else { self.delay_pos + 1 };
        let real = self.delay[self.delay_pos];
        (real, imag)
    }
}