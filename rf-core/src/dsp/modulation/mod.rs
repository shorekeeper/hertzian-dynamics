//! Modulators and demodulators.
//!
//! Each modulator turns a real audio signal (or a key state stream
//! for CW) into a complex baseband signal. Each demodulator inverts
//! its modulator. None of the modules here add a carrier frequency:
//! the output is centered on DC. Carrier placement lives in the
//! mixer that is part of the spectrum manager 
//!
//! Modulators and demodulators are stateful; reset is exposed where
//! state exists.

pub mod am;
pub mod fm;
pub mod ssb;
pub mod cw;
pub mod jam;

pub use am::{AmDemodulator, AmModulator};
pub use cw::{CwDemodulator, CwModulator};
pub use fm::{FmDemodulator, FmModulator};
pub use jam::{JamProfile, NoiseJammer};
pub use ssb::{Sideband, SsbDemodulator, SsbModulator};

/// Modulator that takes a real audio signal and produces complex
/// baseband. Implementations document their input amplitude range
/// expectation (typically `[-1, 1]`).
pub trait AudioModulator {
    /// Modulate `audio.len()` samples into `baseband`. The two
    /// slices must have the same length; implementations panic on
    /// mismatch.
    fn modulate(&mut self, audio: &[f32], baseband: &mut [crate::types::iq::Iq]);

    /// Reset any internal accumulator.
    fn reset(&mut self);
}

/// Demodulator that recovers audio from baseband.
pub trait AudioDemodulator {
    /// Demodulate `baseband.len()` samples into `audio`.
    fn demodulate(&mut self, baseband: &[crate::types::iq::Iq], audio: &mut [f32]);

    /// Reset any internal state.
    fn reset(&mut self);
}

/// Source that emits baseband samples without an audio input.
/// Implemented by the noise jammer.
pub trait BasebandSource {
    /// Fill the slice with the next batch of samples.
    fn generate(&mut self, baseband: &mut [crate::types::iq::Iq]);

    /// Reset any internal state.
    fn reset(&mut self);
}