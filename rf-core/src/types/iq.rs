//! Complex baseband sample, the working currency of every DSP stage
//! in rf-core.
//!
//! Convention follows standard electronics texts: `i` is the in phase
//! component, `q` is the quadrature component. The represented
//! complex value is `i + jq` with `j` the imaginary unit. All DSP
//! arithmetic is done in single precision because the dynamic range
//! a radio receiver presents to its demodulator is well within the
//! 24 bits of f32 mantissa even with a worst case 60 dB AGC swing.

/// One complex sample in 32 bit floating point.
///
/// Memory layout is guaranteed to be exactly two contiguous f32
/// values with no padding (8 bytes total, 4 byte alignment). The
/// layout matches GLSL `vec2` when uploaded as a storage buffer
/// element under std430 rules.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default, PartialEq)]
pub struct Iq {
    /// In phase component.
    pub i: f32,
    /// Quadrature component.
    pub q: f32,
}

impl Iq {
    /// Convenience constructor.
    #[inline]
    pub const fn new(i: f32, q: f32) -> Self {
        Self { i, q }
    }

    /// Additive identity. Used as the seed value of every mixer
    /// summation loop.
    pub const ZERO: Self = Self { i: 0.0, q: 0.0 };

    /// Squared magnitude, `i^2 + q^2`. No square root. Used by power
    /// detectors, AGC and noise floor estimators where the sqrt
    /// would only undo a later log.
    #[inline]
    pub fn power(self) -> f32 {
        self.i * self.i + self.q * self.q
    }

    /// Magnitude, `sqrt(i^2 + q^2)`. Used by envelope AM demodulators.
    #[inline]
    pub fn magnitude(self) -> f32 {
        self.power().sqrt()
    }

    /// Instantaneous phase in radians, range `(-pi, pi]`. The four
    /// quadrant atan2 form is used so the result is unambiguous
    /// across the full complex plane. FM and PM demodulators take
    /// the discrete time derivative of this value.
    #[inline]
    pub fn phase(self) -> f32 {
        self.q.atan2(self.i)
    }

    /// Complex multiplication.
    /// `(a + jb) * (c + jd) = (ac - bd) + j(ad + bc)`.
    /// Used by frequency mixers to translate carriers.
    #[inline]
    pub fn mul(self, rhs: Iq) -> Iq {
        Iq {
            i: self.i * rhs.i - self.q * rhs.q,
            q: self.i * rhs.q + self.q * rhs.i,
        }
    }

    /// Complex conjugate, equivalent to negating the quadrature.
    /// Used when mixing down by multiplying with the conjugate of
    /// the local oscillator.
    #[inline]
    pub fn conj(self) -> Iq {
        Iq { i: self.i, q: -self.q }
    }

    /// Component wise addition. Interference summation uses this in
    /// the inner loop of the spectrum mixer.
    #[inline]
    pub fn add(self, rhs: Iq) -> Iq {
        Iq { i: self.i + rhs.i, q: self.q + rhs.q }
    }

    /// Scalar multiplication. Path loss, antenna gain and AGC apply
    /// their corrections through this.
    #[inline]
    pub fn scale(self, k: f32) -> Iq {
        Iq { i: self.i * k, q: self.q * k }
    }

        /// Component wise subtraction. Used by FFT butterflies.
    #[inline]
    pub fn sub(self, rhs: Iq) -> Iq {
        Iq { i: self.i - rhs.i, q: self.q - rhs.q }
    }

    /// Unit modulus complex from a phase angle in radians.
    /// Equivalent to `cos(angle) + j sin(angle)`. Used by NCO,
    /// twiddle factor generation and mixer math.
    #[inline]
    pub fn cis(angle: f32) -> Iq {
        Iq { i: angle.cos(), q: angle.sin() }
    }

    /// Multiplication by a complex value followed by addition.
    /// Helper for FIR convolution inner loops; the compiler is free
    /// to fuse it into FMA on supported targets.
    #[inline]
    pub fn mul_add(self, rhs: Iq, acc: Iq) -> Iq {
        Iq {
            i: acc.i + self.i * rhs.i - self.q * rhs.q,
            q: acc.q + self.i * rhs.q + self.q * rhs.i,
        }
    }
}

// Compile time guard on the GPU layout assumption. If a future edit
// reorders fields or changes the field type, this fails to build.
const _: () = {
    assert!(core::mem::size_of::<Iq>() == 8);
    assert!(core::mem::align_of::<Iq>() == 4);
};