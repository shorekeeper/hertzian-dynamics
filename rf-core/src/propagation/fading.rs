//! Rayleigh multipath fading.
//!
//! For a multipath channel with many scatterers and no dominant
//! line of sight, the received envelope follows a Rayleigh
//! distribution. The simplest practical generator is:
//!
//!   X, Y ~ N(0, sigma^2 / 2)  (independent)
//!   r = sqrt(X^2 + Y^2)
//!
//! `r` is the linear amplitude multiplier; the corresponding power
//! gain is `r^2`. The mean of `r^2` is `sigma^2`, so passing
//! `sigma = 1.0` keeps the long term average power gain at unity.
//!
//! The generator caches per receiver state so the fade pattern is
//! correlated frame to frame instead of completely white. The
//! correlation comes from a slow first order IIR on each Gaussian
//! rail; in real channels the autocorrelation is more nuanced (Jakes
//! model) but the IIR sounds right and is cheap.

use crate::dsp::noise::GaussianNoise;

/// Stateful Rayleigh fade generator.
#[derive(Clone, Debug)]
pub struct RayleighFading {
    gauss: GaussianNoise,
    last_re: f32,
    last_im: f32,
    coherence_alpha: f32,
}

impl RayleighFading {
    /// `seed` controls the underlying PRNG; pin it for
    /// reproducibility in tests. `coherence_alpha` is the IIR
    /// blending factor, in [0, 1). A value near zero gives
    /// uncorrelated draws every call; 0.95 gives a slow lazy fade.
    pub fn new(seed: u64, coherence_alpha: f32) -> Self {
        debug_assert!((0.0..1.0).contains(&coherence_alpha));
        Self {
            gauss: GaussianNoise::new(seed, 1.0 / core::f32::consts::SQRT_2),
            last_re: 0.0,
            last_im: 0.0,
            coherence_alpha,
        }
    }

    /// Reset to no history.
    pub fn reset(&mut self) {
        self.last_re = 0.0;
        self.last_im = 0.0;
    }

    /// Draw the next amplitude multiplier.
    pub fn sample(&mut self) -> f32 {
        let nx = self.gauss.sample();
        let ny = self.gauss.sample();
        let a = self.coherence_alpha;
        self.last_re = a * self.last_re + (1.0 - a) * nx;
        self.last_im = a * self.last_im + (1.0 - a) * ny;
        (self.last_re * self.last_re + self.last_im * self.last_im).sqrt()
    }

    /// Same as `sample` but reported in decibels. A draw of unity
    /// power yields zero dB. The function clamps to a floor of
    /// -60 dB to avoid f32 log10 blowups on extremely deep fades.
    pub fn sample_db(&mut self) -> f32 {
        let r = self.sample();
        if r <= 1e-3 {
            return -60.0;
        }
        20.0 * r.log10()
    }
}