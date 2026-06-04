//! Cooley Tukey radix 2 in place FFT, CPU reference implementation.
//!
//! Input length must be a power of two. The forward transform uses
//! the engineering sign convention `X[k] = sum x[n] exp(-j 2 pi k n
//! / N)` and is left unnormalised. The inverse transform divides by
//! `N`, so `ifft(fft(x)) == x` modulo rounding.
//!
//! Twiddle factors are computed on the fly with `f32::sin_cos`. For
//! the sizes the engine will use (up to a few thousand), a table is
//! not faster on modern CPUs once the trig calls vectorise.

use crate::error::{Result, RfCoreError};
use crate::types::iq::Iq;

/// Transform direction.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum FftDirection {
    /// Forward DFT, no normalisation.
    Forward,
    /// Inverse DFT, divides output by `N`.
    Inverse,
}

/// In place power of two FFT.
///
/// Returns `InvalidArgument` if the length is not a power of two or
/// is zero. The function panics on slices longer than `u32::MAX`,
/// which is well above any realistic DSP block size.
pub fn fft(data: &mut [Iq], direction: FftDirection) -> Result<()> {
    let n = data.len();
    if n == 0 {
        return Err(RfCoreError::InvalidArgument("FFT length is zero"));
    }
    if !n.is_power_of_two() {
        return Err(RfCoreError::InvalidArgument("FFT length is not a power of two"));
    }
    let log_n = n.trailing_zeros() as usize;

    bit_reverse_in_place(data, log_n);

    let sign = match direction {
        FftDirection::Forward => -1.0_f32,
        FftDirection::Inverse => 1.0_f32,
    };

    let mut size: usize = 2;
    while size <= n {
        let half = size / 2;
        let angle_step = sign * core::f32::consts::TAU / size as f32;
        let mut start = 0;
        while start < n {
            for k in 0..half {
                let angle = angle_step * k as f32;
                let (s, c) = angle.sin_cos();
                let twiddle = Iq { i: c, q: s };
                let i_top = start + k;
                let i_bot = i_top + half;
                let t = data[i_bot].mul(twiddle);
                let u = data[i_top];
                data[i_top] = u.add(t);
                data[i_bot] = u.sub(t);
            }
            start += size;
        }
        size <<= 1;
    }

    if direction == FftDirection::Inverse {
        let scale = 1.0_f32 / n as f32;
        for s in data.iter_mut() {
            *s = s.scale(scale);
        }
    }
    Ok(())
}

/// Convenience alias.
pub fn ifft(data: &mut [Iq]) -> Result<()> {
    fft(data, FftDirection::Inverse)
}

fn bit_reverse_in_place(data: &mut [Iq], log_n: usize) {
    let n = data.len();
    for i in 0..n {
        let j = reverse_bits(i as u32, log_n) as usize;
        if i < j {
            data.swap(i, j);
        }
    }
}

#[inline]
fn reverse_bits(mut x: u32, bits: usize) -> u32 {
    // Reverses the low `bits` of `x`. Equivalent to `x.reverse_bits() >> (32 - bits)`
    // but kept explicit for clarity in the few sizes we ever call it with.
    let mut r: u32 = 0;
    for _ in 0..bits {
        r = (r << 1) | (x & 1);
        x >>= 1;
    }
    r
}