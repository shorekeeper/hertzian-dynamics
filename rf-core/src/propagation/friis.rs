//! Friis free space transmission.
//!
//! Reference equation: Pr / Pt = Gt * Gr * (lambda / (4 pi d))^2.
//! In decibels:
//!   FSPL_dB(d, f) = 20 log10(d) + 20 log10(f) + 20 log10(4 pi / c)
//!                 = 20 log10(d) + 20 log10(f) - 147.55
//! with d in metres and f in hertz. The constant -147.55 dB comes
//! from 20 log10(4 pi / c). We use this exact constant rather than
//! the popular 32.44 dB form because the popular form assumes
//! kilometres and megahertz, which are not the engine's native
//! units.
//!
//! Antenna gains are not handled here. The solver applies them
//! separately so the breakdown stays readable.

use core::f32::consts::PI;

/// Speed of light in vacuum, metres per second.
pub const SPEED_OF_LIGHT_M_S: f32 = 299_792_458.0;

/// Wavelength of a frequency in metres. Panics on non finite or non
/// positive input; both are bugs in upstream code.
#[inline]
pub fn wavelength_m(frequency_hz: f64) -> f64 {
    debug_assert!(frequency_hz > 0.0 && frequency_hz.is_finite());
    SPEED_OF_LIGHT_M_S as f64 / frequency_hz
}

/// Free space path loss in decibels.
///
/// Returns 0.0 dB for distances below half a wavelength because the
/// Friis equation is a far field result and the engine has no
/// meaningful behaviour to model in the near field. The cap also
/// avoids the negative dB values the bare formula produces at very
/// small distances.
pub fn free_space_loss_db(distance_m: f32, frequency_hz: f64) -> f32 {
    let lambda = wavelength_m(frequency_hz) as f32;
    if distance_m <= 0.5 * lambda {
        return 0.0;
    }
    20.0 * (4.0 * PI * distance_m / lambda).log10()
}

/// Free space path loss as a linear ratio Pr / Pt assuming isotropic
/// antennas. Convenience for callers that prefer linear arithmetic.
pub fn free_space_loss_linear(distance_m: f32, frequency_hz: f64) -> f32 {
    let lambda = wavelength_m(frequency_hz) as f32;
    if distance_m <= 0.5 * lambda {
        return 1.0;
    }
    let ratio = lambda / (4.0 * PI * distance_m);
    ratio * ratio
}