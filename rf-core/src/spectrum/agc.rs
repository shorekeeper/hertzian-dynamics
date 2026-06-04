//! Automatic gain control.
//!
//! Tracks the envelope of the input with two time constants: a
//! short attack constant for when the envelope grows, and a longer
//! release constant for when it shrinks. Reference values for
//! communications receivers are 1 to 10 ms attack and 100 ms to
//! a few seconds release; broadcast receivers prefer 60 to 100 ms
//! attack to avoid pumping on speech consonants.
//!
//! The gain is the ratio of a fixed target amplitude to the
//! current envelope estimate. Outputs are clamped by a maximum
//! gain so a fade to silence does not boost the noise floor by
//! 80 dB and blow the demodulator.
//!
//! IIR coefficient for a time constant tau, given sample rate fs:
//!     a = exp(-1 / (fs * tau))
//! Update: env[n] = a * env[n - 1] + (1 - a) * |x[n]|.

use crate::types::iq::Iq;

/// AGC parameters.
#[derive(Copy, Clone, Debug)]
pub struct AgcConfig {
    /// Target output amplitude. The AGC drives the envelope of the
    /// output toward this value. Linear units, same scale as the
    /// input Iq magnitude. 0.5 keeps headroom against clipping in
    /// downstream demodulators that expect |x| <= 1.
    pub target_amplitude: f32,
    /// Attack time constant in seconds. The envelope rises toward
    /// a new larger input with this responsiveness.
    pub attack_seconds: f32,
    /// Release time constant in seconds. The envelope falls toward
    /// a new smaller input on this scale.
    pub release_seconds: f32,
    /// Maximum linear gain the AGC is allowed to apply. Caps the
    /// noise floor boost when the envelope estimate decays into
    /// silence. 80 dB (10_000) is a common reference.
    pub max_gain: f32,
    /// Minimum linear gain. Mostly cosmetic; prevents the AGC from
    /// fully muting on a brief overload.
    pub min_gain: f32,
}

impl Default for AgcConfig {
    fn default() -> Self {
        Self {
            target_amplitude: 0.5,
            attack_seconds: 0.005,
            release_seconds: 0.5,
            max_gain: 10_000.0,
            min_gain: 0.001,
        }
    }
}

/// Stateful AGC.
///
/// Reset is exposed for the manager to clear stale state when a
/// receiver retunes across a band and starts seeing wildly
/// different envelopes.
#[derive(Clone, Debug)]
pub struct Agc {
    config: AgcConfig,
    envelope: f32,
    attack_coeff: f32,
    release_coeff: f32,
    sample_rate_hz: f32,
    /// True until the first sample is processed since construction
    /// or reset. The first sample seeds the envelope directly with
    /// its own magnitude so the AGC reaches the correct gain in one
    /// step. Without this flag the choice of initial envelope is a
    /// losing proposition: too high a seed traps the AGC in slow
    /// release mode for many chunks; too low a seed causes a
    /// max-gain spike on sample one. Letting the first sample
    /// dictate the seed sidesteps both failure modes.
    pristine: bool,
}

impl Agc {
    /// Build an AGC for the given engine sample rate. The envelope
    /// estimate starts at zero and is seeded by the first sample
    /// fed through `process`. Until that first sample the gain is
    /// undefined; callers must not query `current_gain` before
    /// at least one `process` call (the function still returns a
    /// finite, max-gain-clamped value, but it has no relation to
    /// any real signal).
    pub fn new(config: AgcConfig, sample_rate_hz: f32) -> Self {
        assert!(sample_rate_hz > 0.0);
        Self {
            attack_coeff: time_constant_to_coeff(config.attack_seconds, sample_rate_hz),
            release_coeff: time_constant_to_coeff(config.release_seconds, sample_rate_hz),
            envelope: 0.0,
            pristine: true,
            sample_rate_hz,
            config,
        }
    }

    /// Reset the envelope estimate so the next sample seeds it
    /// from scratch. Used by the manager when a receiver retunes
    /// across a band and the previous envelope estimate is no
    /// longer meaningful.
    pub fn reset(&mut self) {
        self.envelope = 0.0;
        self.pristine = true;
    }
    /// Current envelope estimate. Useful for the S-meter display.
    pub fn envelope(&self) -> f32 {
        self.envelope
    }

    /// Current applied gain in linear units.
    pub fn current_gain(&self) -> f32 {
        let raw = self.config.target_amplitude / self.envelope.max(1e-9);
        raw.clamp(self.config.min_gain, self.config.max_gain)
    }

    /// Process one sample. Updates the envelope estimate and
    /// returns the scaled output.
    ///
    /// On the very first sample since construction or reset the
    /// envelope is set directly to the sample magnitude, so the
    /// AGC enters steady state immediately and the output sits on
    /// the target amplitude without a startup transient.
    #[inline]
    pub fn process(&mut self, x: Iq) -> Iq {
        let mag = x.magnitude();
        if self.pristine {
            self.envelope = mag.max(1e-12);
            self.pristine = false;
        } else {
            let coeff = if mag > self.envelope {
                self.attack_coeff
            } else {
                self.release_coeff
            };
            self.envelope = coeff * self.envelope + (1.0 - coeff) * mag;
        }
        let gain = (self.config.target_amplitude / self.envelope.max(1e-9))
            .clamp(self.config.min_gain, self.config.max_gain);
        x.scale(gain)
    }

    /// Block process in place.
    pub fn process_block(&mut self, samples: &mut [Iq]) {
        for s in samples.iter_mut() {
            *s = self.process(*s);
        }
    }

    /// Read back the sample rate the AGC was built against. The
    /// manager uses this to detect mismatches with the engine
    /// rate at runtime.
    pub fn sample_rate_hz(&self) -> f32 {
        self.sample_rate_hz
    }
}

/// Convert an exponential time constant (seconds) to the IIR
/// blending coefficient `a` of `y[n] = a y[n-1] + (1 - a) x[n]`.
/// Very short time constants approach zero coefficient (no
/// smoothing); long time constants approach one (heavy smoothing).
fn time_constant_to_coeff(tau_seconds: f32, fs_hz: f32) -> f32 {
    if tau_seconds <= 0.0 {
        return 0.0;
    }
    (-1.0 / (fs_hz * tau_seconds)).exp()
}