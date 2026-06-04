//! Biquad IIR filters and a Butterworth low pass cascade.
//!
//! Single biquad uses Direct Form II Transposed for numerical
//! stability under f32. Coefficient formulas come from the
//! Bristow Johnson Audio EQ Cookbook with `a0` already normalised
//! to one.
//!
//! The Butterworth low pass of order `N` is built as a cascade of
//! `N/2` biquads (plus one first order section if `N` is odd). The
//! Q of the k th section comes from the standard analog prototype
//! pole layout on the unit circle.

use core::f32::consts::PI;

use crate::types::iq::Iq;

/// One biquad section in DFII T form. Real coefficients, real state.
#[derive(Copy, Clone, Debug)]
pub struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

impl Biquad {
    /// Build from already normalised coefficients (a0 = 1).
    pub fn from_coeffs(b0: f32, b1: f32, b2: f32, a1: f32, a2: f32) -> Self {
        Self { b0, b1, b2, a1, a2, z1: 0.0, z2: 0.0 }
    }

    /// RBJ cookbook low pass.
    pub fn lowpass(fs_hz: f32, fc_hz: f32, q: f32) -> Self {
        let (cos_w, alpha) = cookbook_intermediates(fs_hz, fc_hz, q);
        let a0 = 1.0 + alpha;
        let b0 = (1.0 - cos_w) * 0.5;
        let b1 = 1.0 - cos_w;
        let b2 = (1.0 - cos_w) * 0.5;
        let a1 = -2.0 * cos_w;
        let a2 = 1.0 - alpha;
        Self::from_coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /// RBJ cookbook high pass.
    pub fn highpass(fs_hz: f32, fc_hz: f32, q: f32) -> Self {
        let (cos_w, alpha) = cookbook_intermediates(fs_hz, fc_hz, q);
        let a0 = 1.0 + alpha;
        let b0 = (1.0 + cos_w) * 0.5;
        let b1 = -(1.0 + cos_w);
        let b2 = (1.0 + cos_w) * 0.5;
        let a1 = -2.0 * cos_w;
        let a2 = 1.0 - alpha;
        Self::from_coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /// RBJ cookbook band pass with constant 0 dB peak gain.
    pub fn bandpass(fs_hz: f32, fc_hz: f32, q: f32) -> Self {
        let (cos_w, alpha) = cookbook_intermediates(fs_hz, fc_hz, q);
        let a0 = 1.0 + alpha;
        let b0 = alpha;
        let b1 = 0.0;
        let b2 = -alpha;
        let a1 = -2.0 * cos_w;
        let a2 = 1.0 - alpha;
        Self::from_coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /// Reset internal state.
    pub fn reset(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }

    /// Process one sample. DFII T:
    /// `y = b0 * x + z1`
    /// `z1 = b1 * x - a1 * y + z2`
    /// `z2 = b2 * x - a2 * y`
    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }
}

fn cookbook_intermediates(fs_hz: f32, fc_hz: f32, q: f32) -> (f32, f32) {
    assert!(fs_hz > 0.0);
    assert!(fc_hz > 0.0 && fc_hz < fs_hz * 0.5);
    assert!(q > 0.0);
    let w0 = 2.0 * PI * fc_hz / fs_hz;
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * q);
    (cos_w, alpha)
}

/// Butterworth low pass cascade.
///
/// For an analog Butterworth low pass of order `N`, the poles sit at
/// angles `theta_k = pi (2k + N - 1) / (2N)` on the unit circle in
/// the left half s plane. Each conjugate pair becomes a digital
/// biquad with `Q = 1 / (2 sin(theta_k - pi))` after bilinear
/// transform. We use the simpler equivalent form
/// `Q_k = 1 / (2 sin(pi (2k - 1) / (2N)))`, k = 1..N/2.
///
/// For odd `N` a leading first order pole on the negative real axis
/// becomes a one pole IIR; we approximate it with a biquad of very
/// high Q to keep the cascade homogeneous.
#[derive(Clone, Debug)]
pub struct ButterworthLp {
    sections: Vec<Biquad>,
}

impl ButterworthLp {
    /// Create a low pass of `order` sections at `fc_hz`. Order must
    /// be between 1 and 8 inclusive. Higher orders are achievable
    /// but `f32` numerical headroom shrinks; callers wanting more
    /// selectivity should chain instances.
    pub fn new(order: usize, fs_hz: f32, fc_hz: f32) -> Self {
        assert!((1..=8).contains(&order), "Butterworth order out of range");
        let mut sections = Vec::with_capacity((order + 1) / 2);
        let pairs = order / 2;
        for k in 1..=pairs {
            let theta = PI * (2 * k - 1) as f32 / (2 * order) as f32;
            let q = 1.0 / (2.0 * theta.sin());
            sections.push(Biquad::lowpass(fs_hz, fc_hz, q));
        }
        if order & 1 == 1 {
            // Odd order: add a section with very low Q to approximate
            // the leading first order pole. Q = 0.5 gives a real
            // pole pair at -1 in the prototype, which is the closest
            // a biquad gets to a single first order section.
            sections.push(Biquad::lowpass(fs_hz, fc_hz, 0.5));
        }
        Self { sections }
    }

    /// Reset all sections.
    pub fn reset(&mut self) {
        for s in self.sections.iter_mut() {
            s.reset();
        }
    }

    /// Process one sample through the cascade.
    #[inline]
    pub fn process(&mut self, mut x: f32) -> f32 {
        for s in self.sections.iter_mut() {
            x = s.process(x);
        }
        x
    }

    /// Block process, real to real.
    pub fn process_block(&mut self, input: &[f32], output: &mut [f32]) {
        assert_eq!(input.len(), output.len(), "Butterworth block size mismatch");
        for (xi, yi) in input.iter().zip(output.iter_mut()) {
            *yi = self.process(*xi);
        }
    }

    /// Process a complex IQ stream by running independent state
    /// machines on the I and Q rails. Allocates internally on the
    /// first call to hold the second rail; reuse the same instance
    /// across blocks to avoid that cost.
    pub fn process_block_iq(&mut self, input: &[Iq], output: &mut [Iq], q_rail: &mut Self) {
        assert_eq!(input.len(), output.len(), "IIR IQ block size mismatch");
        for (x, y) in input.iter().zip(output.iter_mut()) {
            y.i = self.process(x.i);
            y.q = q_rail.process(x.q);
        }
    }
}