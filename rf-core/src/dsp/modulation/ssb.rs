//! Single sideband modulation.
//!
//! At baseband, an upper sideband signal is the analytic signal of
//! the audio: `s(t) = a(t) + j h(t)` where `h(t)` is the Hilbert
//! transform of `a(t)`. Mixing this against a real carrier at `fc`
//! places the audio band entirely above `fc`. Lower sideband is the
//! conjugate, `s(t) = a(t) - j h(t)`.
//!
//! Demodulation at baseband, with the receiver tuned exactly to the
//! transmitter carrier, recovers the audio as `real(s(t))` for USB
//! and `real(conj(s(t)))` for LSB. A tuning offset translates into
//! a frequency shift in the demodulated audio, the classic "Donald
//! Duck" sound of mistuned SSB.

use crate::dsp::hilbert::HilbertFir;
use crate::dsp::modulation::{AudioDemodulator, AudioModulator};
use crate::dsp::window::WindowKind;
use crate::types::iq::Iq;

/// Which sideband to keep.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum Sideband {
    /// Upper sideband. Audio sits above the carrier frequency.
    Upper,
    /// Lower sideband. Audio sits below the carrier frequency.
    Lower,
}

/// SSB modulator.
#[derive(Clone, Debug)]
pub struct SsbModulator {
    sideband: Sideband,
    hilbert: HilbertFir,
}

impl SsbModulator {
    /// `taps` is the Hilbert FIR length. 65 to 127 is typical for
    /// voice. Longer is sharper but more delay.
    pub fn new(sideband: Sideband, taps: usize) -> Self {
        Self {
            sideband,
            hilbert: HilbertFir::new(taps, WindowKind::Blackman),
        }
    }

    /// Group delay introduced by the Hilbert transformer, in samples.
    /// The audio rail in `process` is delayed by the same amount so
    /// the resulting analytic pair is phase aligned, but downstream
    /// blocks may need to account for this delay overall.
    pub fn group_delay_samples(&self) -> usize {
        self.hilbert.group_delay_samples()
    }
}

impl AudioModulator for SsbModulator {
    fn modulate(&mut self, audio: &[f32], baseband: &mut [Iq]) {
        assert_eq!(audio.len(), baseband.len(), "SSB block size mismatch");
        for (a, b) in audio.iter().zip(baseband.iter_mut()) {
            let (real, imag) = self.hilbert.process(*a);
            let q = match self.sideband {
                Sideband::Upper => imag,
                Sideband::Lower => -imag,
            };
            *b = Iq { i: real, q };
        }
    }
    fn reset(&mut self) {
        self.hilbert.reset();
    }
}

/// SSB demodulator. Pure baseband recovery, no carrier reinjection.
#[derive(Clone, Debug)]
pub struct SsbDemodulator {
    sideband: Sideband,
}

impl SsbDemodulator {
    /// Trivial constructor: the demodulator carries no state beyond
    /// the sideband choice.
    pub fn new(sideband: Sideband) -> Self {
        Self { sideband }
    }
}

impl AudioDemodulator for SsbDemodulator {
    fn demodulate(&mut self, baseband: &[Iq], audio: &mut [f32]) {
        assert_eq!(baseband.len(), audio.len(), "SSB demod block size mismatch");
        for (b, a) in baseband.iter().zip(audio.iter_mut()) {
            // real(b) for USB; for LSB the conjugate flips the sign
            // of q which does not affect the real part, so both
            // sidebands recover the audio identically once the
            // matching modulator is used end to end. The sideband
            // selector still matters when the receiver looks at a
            // mistuned signal in the spectrum manager.
            let _ = self.sideband;
            *a = b.i;
        }
    }
    fn reset(&mut self) {}
}