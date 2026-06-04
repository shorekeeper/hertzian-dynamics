//! Continuous wave keying.
//!
//! On the transmit side: input is a stream of key states (`1.0` for
//! key down, `0.0` for key up). The modulator turns transitions into
//! a smoothly ramped envelope to avoid key clicks that would smear
//! across the band. Output baseband is the envelope on the real
//! axis; the carrier sits at zero on baseband.
//!
//! On the receive side: detect envelope above a threshold, then mix
//! the magnitude with an internal sidetone oscillator so the audio
//! output is an audible tone modulated by the key state. The
//! sidetone pitch is conventionally 600 to 800 Hz.

use crate::dsp::modulation::{AudioDemodulator, AudioModulator};
use crate::dsp::osc::Nco;
use crate::types::iq::Iq;

/// CW keyer with raised cosine attack and release.
#[derive(Clone, Debug)]
pub struct CwModulator {
    envelope: f32,
    target: f32,
    rise_step: f32,
    fall_step: f32,
}

impl CwModulator {
    /// `ramp_seconds` controls the duration of the attack and
    /// release. 5 ms is a common value that keeps the keying click
    /// spectrum below the ear.
    pub fn new(ramp_seconds: f32, sample_rate_hz: f32) -> Self {
        debug_assert!(ramp_seconds > 0.0);
        debug_assert!(sample_rate_hz > 0.0);
        let n = (ramp_seconds * sample_rate_hz).max(1.0);
        let step = 1.0 / n;
        Self {
            envelope: 0.0,
            target: 0.0,
            rise_step: step,
            fall_step: step,
        }
    }
}

impl AudioModulator for CwModulator {
    fn modulate(&mut self, audio: &[f32], baseband: &mut [Iq]) {
        assert_eq!(audio.len(), baseband.len(), "CW block size mismatch");
        for (a, b) in audio.iter().zip(baseband.iter_mut()) {
            self.target = if *a > 0.5 { 1.0 } else { 0.0 };
            if self.envelope < self.target {
                self.envelope = (self.envelope + self.rise_step).min(self.target);
            } else if self.envelope > self.target {
                self.envelope = (self.envelope - self.fall_step).max(self.target);
            }
            *b = Iq { i: self.envelope, q: 0.0 };
        }
    }
    fn reset(&mut self) {
        self.envelope = 0.0;
        self.target = 0.0;
    }
}

/// CW receiver: envelope detect plus sidetone reinjection.
#[derive(Clone, Debug)]
pub struct CwDemodulator {
    sidetone: Nco,
    threshold: f32,
}

impl CwDemodulator {
    /// `pitch_hz` is the audible sidetone frequency. `threshold` is
    /// the magnitude above which the receiver considers the key as
    /// down; the typical range is `0.05` to `0.2` against a
    /// noise floor of one.
    pub fn new(pitch_hz: f32, sample_rate_hz: f32, threshold: f32) -> Self {
        Self {
            sidetone: Nco::new(pitch_hz, sample_rate_hz),
            threshold,
        }
    }
}

impl AudioDemodulator for CwDemodulator {
    fn demodulate(&mut self, baseband: &[Iq], audio: &mut [f32]) {
        assert_eq!(baseband.len(), audio.len(), "CW demod block size mismatch");
        for (b, a) in baseband.iter().zip(audio.iter_mut()) {
            let env = b.magnitude();
            let gated = if env > self.threshold { env } else { 0.0 };
            let tone = self.sidetone.next_sample();
            *a = gated * tone.i;
        }
    }
    fn reset(&mut self) {
        self.sidetone.reset();
    }
}