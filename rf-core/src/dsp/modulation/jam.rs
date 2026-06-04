//! Wide band noise jammer (the Duga effect, in spirit).
//!
//! Emits coloured noise baseband. Two profiles are provided: a flat
//! Gaussian fill across the configured bandwidth, and a pulsed
//! variant that gates the noise at a chosen rate to mimic the
//! characteristic stutter of the Soviet OTH radar. The receiver
//! does not care which profile is in use; what matters is the
//! occupied bandwidth and the average power.

use crate::dsp::modulation::BasebandSource;
use crate::dsp::noise::GaussianNoise;
use crate::types::iq::Iq;

/// Selection of jammer behaviour.
#[derive(Copy, Clone, Debug)]
pub enum JamProfile {
    /// Continuous Gaussian noise.
    Continuous,
    /// Pulsed Gaussian noise. `rate_hz` is the gate frequency, the
    /// duty cycle is fixed at 50 percent for an approximation of
    /// the Duga timing.
    Pulsed { rate_hz: f32 },
}

/// Noise jammer source.
#[derive(Clone, Debug)]
pub struct NoiseJammer {
    noise: GaussianNoise,
    profile: JamProfile,
    sample_rate_hz: f32,
    phase: f32,
}

impl NoiseJammer {
    /// `sigma` is the per channel noise standard deviation. The
    /// resulting complex sample has Rayleigh magnitude with the
    /// same `sigma` parameter.
    pub fn new(seed: u64, sigma: f32, profile: JamProfile, sample_rate_hz: f32) -> Self {
        Self {
            noise: GaussianNoise::new(seed, sigma),
            profile,
            sample_rate_hz,
            phase: 0.0,
        }
    }

    /// Override the noise level at runtime, for example to scale
    /// with operator commanded transmit power.
    pub fn set_sigma(&mut self, sigma: f32) {
        self.noise.set_sigma(sigma);
    }
}

impl BasebandSource for NoiseJammer {
    fn generate(&mut self, baseband: &mut [Iq]) {
        match self.profile {
            JamProfile::Continuous => {
                self.noise.fill_iq(baseband);
            }
            JamProfile::Pulsed { rate_hz } => {
                let step = rate_hz / self.sample_rate_hz;
                for slot in baseband.iter_mut() {
                    let gate = if self.phase < 0.5 { 1.0 } else { 0.0 };
                    *slot = Iq {
                        i: self.noise.sample() * gate,
                        q: self.noise.sample() * gate,
                    };
                    self.phase += step;
                    if self.phase >= 1.0 {
                        self.phase -= 1.0;
                    }
                }
            }
        }
    }
    fn reset(&mut self) {
        self.phase = 0.0;
    }
}