//! Frequency modulation, used for VHF voice (narrowband, deviation
//! 2.5 to 5 kHz) and broadcast FM (wideband, deviation 75 kHz).
//!
//! Modulator: integrate the audio into a phase, emit the complex
//! exponential of that phase. Demodulator: phase delta between
//! consecutive samples gives instantaneous frequency, which is
//! proportional to the audio.
//!
//! The phase delta is recovered without an `atan2` per sample by
//! computing `arg(z[n] conj z[n - 1])` and pulling the angle from
//! the resulting complex number. This is the standard differential
//! demodulator and is numerically robust because it never has to
//! unwrap a 2 pi discontinuity.

use core::f32::consts::TAU;

use crate::dsp::modulation::{AudioDemodulator, AudioModulator};
use crate::types::iq::Iq;

/// FM modulator. State is the running phase accumulator.
#[derive(Clone, Debug)]
pub struct FmModulator {
    phase: f64,
    deviation_hz: f32,
    sample_rate_hz: f32,
}

impl FmModulator {
    /// `deviation_hz` is the peak frequency deviation at `audio = 1`.
    pub fn new(deviation_hz: f32, sample_rate_hz: f32) -> Self {
        debug_assert!(deviation_hz > 0.0);
        debug_assert!(sample_rate_hz > 0.0);
        Self { phase: 0.0, deviation_hz, sample_rate_hz }
    }
}

impl AudioModulator for FmModulator {
    fn modulate(&mut self, audio: &[f32], baseband: &mut [Iq]) {
        assert_eq!(audio.len(), baseband.len(), "FM block size mismatch");
        let step_per_unit = TAU as f64 * self.deviation_hz as f64 / self.sample_rate_hz as f64;
        for (a, b) in audio.iter().zip(baseband.iter_mut()) {
            self.phase += step_per_unit * *a as f64;
            if self.phase > core::f64::consts::PI {
                self.phase -= core::f64::consts::TAU;
            } else if self.phase < -core::f64::consts::PI {
                self.phase += core::f64::consts::TAU;
            }
            *b = Iq {
                i: self.phase.cos() as f32,
                q: self.phase.sin() as f32,
            };
        }
    }
    fn reset(&mut self) {
        self.phase = 0.0;
    }
}

/// FM differential demodulator.
#[derive(Clone, Debug)]
pub struct FmDemodulator {
    last: Iq,
    gain: f32,
}

impl FmDemodulator {
    /// `deviation_hz` and `sample_rate_hz` must match the modulator
    /// to recover `audio` on the original amplitude scale.
    pub fn new(deviation_hz: f32, sample_rate_hz: f32) -> Self {
        debug_assert!(deviation_hz > 0.0);
        debug_assert!(sample_rate_hz > 0.0);
        // peak phase step per sample at audio = 1 is 2 pi D / fs.
        // inverse maps a phase step back to audio in [-1, 1].
        let gain = sample_rate_hz / (TAU * deviation_hz);
        Self { last: Iq::new(1.0, 0.0), gain }
    }
}

impl AudioDemodulator for FmDemodulator {
    fn demodulate(&mut self, baseband: &[Iq], audio: &mut [f32]) {
        assert_eq!(baseband.len(), audio.len(), "FM demod block size mismatch");
        for (b, a) in baseband.iter().zip(audio.iter_mut()) {
            let prod = b.mul(self.last.conj());
            let delta = prod.q.atan2(prod.i);
            *a = delta * self.gain;
            self.last = *b;
        }
    }
    fn reset(&mut self) {
        self.last = Iq::new(1.0, 0.0);
    }
}