//! Numerically controlled oscillator.
//!
//! Produces a unit modulus complex tone `exp(j 2 pi f t)` at the
//! requested frequency and sample rate. Used as the local oscillator
//! in frequency translation, as the carrier reference in modulators,
//! and as the test tone source in examples.
//!
//! Implementation notes
//! --------------------
//!
//! Phase is accumulated in `f64` and wrapped to `(-pi, pi]` once per
//! sample. `f64` is required: at 48 kHz a `f32` accumulator drifts
//! noticeably after a minute because the wraparound step
//! `2 pi f / fs` becomes a denormal fraction of the accumulator.
//! Output samples are converted to `f32` after the trig call.

use core::f64::consts::{PI, TAU};

use crate::types::iq::Iq;

/// Stateful local oscillator.
#[derive(Clone, Debug)]
pub struct Nco {
    phase: f64,
    step: f64,
    sample_rate: f64,
}

impl Nco {
    /// Construct an NCO running at `frequency_hz` against
    /// `sample_rate_hz`. Initial phase is zero.
    pub fn new(frequency_hz: f32, sample_rate_hz: f32) -> Self {
        let mut me = Self {
            phase: 0.0,
            step: 0.0,
            sample_rate: sample_rate_hz as f64,
        };
        me.set_frequency(frequency_hz);
        me
    }

    /// Change the tuned frequency. Phase is preserved, so frequency
    /// shifts are continuous and do not introduce clicks.
    pub fn set_frequency(&mut self, frequency_hz: f32) {
        self.step = TAU * frequency_hz as f64 / self.sample_rate;
    }

    /// Reset the phase accumulator to zero.
    pub fn reset(&mut self) {
        self.phase = 0.0;
    }

    /// Produce one complex sample and advance the phase.
    #[inline]
    pub fn next_sample(&mut self) -> Iq {
        let s = Iq {
            i: self.phase.cos() as f32,
            q: self.phase.sin() as f32,
        };
        self.phase += self.step;
        if self.phase > PI {
            self.phase -= TAU;
        } else if self.phase < -PI {
            self.phase += TAU;
        }
        s
    }

    /// Fill `out` with consecutive samples. The frequency is fixed
    /// for the whole call; for chirps, call repeatedly with
    /// `set_frequency` in between.
    pub fn fill(&mut self, out: &mut [Iq]) {
        for slot in out.iter_mut() {
            *slot = self.next_sample();
        }
    }
}