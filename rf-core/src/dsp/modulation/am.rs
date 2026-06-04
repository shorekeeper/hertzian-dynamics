//! Amplitude modulation with carrier (DSB AM), the broadcast format
//! used on LW, MW and SW radio. The transmitted signal is
//! `s(t) = (1 + m a(t)) cos(omega_c t)` where `a(t)` is the audio
//! normalised to `[-1, 1]` and `m` is the modulation index.
//!
//! On baseband (carrier at zero) the same expression collapses to a
//! purely real envelope `1 + m a(t)`, so I carries the envelope and
//! Q is zero. The receiver detects the magnitude and removes the
//! DC bias.

use crate::dsp::dc_block::DcBlocker;
use crate::dsp::modulation::{AudioDemodulator, AudioModulator};
use crate::types::iq::Iq;

/// AM modulator. Stateless; `reset` is a no op.
#[derive(Copy, Clone, Debug)]
pub struct AmModulator {
    modulation_index: f32,
}

impl AmModulator {
    /// `modulation_index` in `(0, 1]`. One produces 100% modulation
    /// at peak input. Values above one over modulate and the
    /// envelope detector will clip on negative half cycles.
    pub fn new(modulation_index: f32) -> Self {
        debug_assert!(modulation_index > 0.0 && modulation_index <= 1.0);
        Self { modulation_index }
    }
}

impl AudioModulator for AmModulator {
    fn modulate(&mut self, audio: &[f32], baseband: &mut [Iq]) {
        assert_eq!(audio.len(), baseband.len(), "AM block size mismatch");
        let m = self.modulation_index;
        for (a, b) in audio.iter().zip(baseband.iter_mut()) {
            *b = Iq { i: 1.0 + m * *a, q: 0.0 };
        }
    }
    fn reset(&mut self) {}
}

/// AM envelope detector with DC blocking. The output amplitude
/// equals `m a(t)` for inputs that came directly from `AmModulator`.
#[derive(Clone, Debug)]
pub struct AmDemodulator {
    dc_block: DcBlocker,
    modulation_index: f32,
}

impl AmDemodulator {
    /// `modulation_index` should match the transmitter so the
    /// returned audio is on the same scale as the source. The DC
    /// blocker time constant `r` is fixed to 0.999 here, which
    /// gives a cutoff around 8 Hz at 48 kHz.
    pub fn new(modulation_index: f32) -> Self {
        debug_assert!(modulation_index > 0.0);
        Self {
            dc_block: DcBlocker::new(0.999),
            modulation_index,
        }
    }
}

impl AudioDemodulator for AmDemodulator {
    fn demodulate(&mut self, baseband: &[Iq], audio: &mut [f32]) {
        assert_eq!(baseband.len(), audio.len(), "AM demod block size mismatch");
        let inv_m = 1.0 / self.modulation_index;
        for (b, a) in baseband.iter().zip(audio.iter_mut()) {
            let env = b.magnitude();
            // env is 1 + m*audio for an undistorted channel; remove
            // the DC bias and scale back to audio units.
            let centered = env - 1.0;
            *a = self.dc_block.process(centered) * inv_m;
        }
    }
    fn reset(&mut self) {
        self.dc_block.reset();
    }
}