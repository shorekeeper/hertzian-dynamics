//! Noise generators.
//!
//! Two layers:
//!   1. `Xoshiro128PlusPlus`, a small fast PRNG with 128 bits of
//!      state. Reference: Blackman and Vigna, 2018.
//!   2. `GaussianNoise`, a Box Muller transform sitting on top of
//!      `Xoshiro128PlusPlus` and producing `N(0, sigma^2)` samples.
//!
//! Why not the standard library? `rand` would pull in a tree of
//! crates; `std` has no public PRNG. The handful of arithmetic ops
//! below is cheaper than the dependency budget would be.

use core::f32::consts::TAU;

use crate::types::iq::Iq;

/// `xoshiro128++` PRNG. Period `2^128 - 1`. Suitable for general
/// purpose Monte Carlo at game time scales.
///
/// Not cryptographically secure. Do not seed with all zeros; the
/// constructor enforces a nonzero state.
#[derive(Clone, Debug)]
pub struct Xoshiro128PlusPlus {
    s: [u32; 4],
}

impl Xoshiro128PlusPlus {
    /// Seed from a 64 bit value. The expansion to 128 bits uses
    /// SplitMix64 as recommended by the algorithm authors.
    pub fn from_seed(seed: u64) -> Self {
        let mut sm = seed.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut splitmix = || {
            sm = sm.wrapping_add(0x9E37_79B9_7F4A_7C15);
            let mut z = sm;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
            z ^ (z >> 31)
        };
        let a = splitmix();
        let b = splitmix();
        let s = [
            a as u32,
            (a >> 32) as u32,
            b as u32,
            (b >> 32) as u32,
        ];
        let me = Self { s };
        // Guarantee nonzero state.
        if me.s == [0; 4] {
            return Self { s: [1, 0, 0, 0] };
        }
        me
    }

    /// Raw 32 bit output.
    #[inline]
    pub fn next_u32(&mut self) -> u32 {
        let result = self.s[0]
            .wrapping_add(self.s[3])
            .rotate_left(7)
            .wrapping_add(self.s[0]);
        let t = self.s[1] << 9;
        self.s[2] ^= self.s[0];
        self.s[3] ^= self.s[1];
        self.s[1] ^= self.s[2];
        self.s[0] ^= self.s[3];
        self.s[2] ^= t;
        self.s[3] = self.s[3].rotate_left(11);
        result
    }

    /// Uniform float in `[0, 1)`. Uses the top 24 bits, the rest is
    /// discarded so the spacing matches the `f32` mantissa width.
    #[inline]
    pub fn next_f32_unit(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 * (1.0 / (1u32 << 24) as f32)
    }
}

/// Gaussian noise generator built on Box Muller. Each pair of
/// uniform draws becomes a pair of normal samples; one is returned
/// immediately, the other is cached for the next call.
#[derive(Clone, Debug)]
pub struct GaussianNoise {
    rng: Xoshiro128PlusPlus,
    sigma: f32,
    cached: Option<f32>,
}

impl GaussianNoise {
    /// New generator with the given seed and standard deviation.
    pub fn new(seed: u64, sigma: f32) -> Self {
        debug_assert!(sigma >= 0.0);
        Self {
            rng: Xoshiro128PlusPlus::from_seed(seed),
            sigma,
            cached: None,
        }
    }

    /// Update sigma at runtime, for example to track AGC.
    pub fn set_sigma(&mut self, sigma: f32) {
        debug_assert!(sigma >= 0.0);
        self.sigma = sigma;
    }

    /// Draw one sample.
    #[inline]
    pub fn sample(&mut self) -> f32 {
        if let Some(z) = self.cached.take() {
            return z * self.sigma;
        }
        // Avoid log(0) by drawing u1 in the open interval (0, 1].
        let mut u1 = self.rng.next_f32_unit();
        if u1 == 0.0 {
            u1 = f32::MIN_POSITIVE;
        }
        let u2 = self.rng.next_f32_unit();
        let mag = (-2.0 * u1.ln()).sqrt();
        let phase = TAU * u2;
        let z0 = mag * phase.cos();
        let z1 = mag * phase.sin();
        self.cached = Some(z1);
        z0 * self.sigma
    }

    /// Fill a real buffer.
    pub fn fill(&mut self, out: &mut [f32]) {
        for s in out.iter_mut() {
            *s = self.sample();
        }
    }

    /// Fill a complex buffer; I and Q are independent draws so the
    /// resulting complex noise has uniform phase and Rayleigh
    /// distributed magnitude with parameter `sigma`.
    pub fn fill_iq(&mut self, out: &mut [Iq]) {
        for s in out.iter_mut() {
            *s = Iq { i: self.sample(), q: self.sample() };
        }
    }
}