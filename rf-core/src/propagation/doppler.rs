//! Doppler shift.
//!
//! Non relativistic, narrow band approximation:
//!
//!     f_d = (v_r / c) * f_c
//!
//! where `v_r` is the radial velocity of the receiver relative to
//! the transmitter, positive when they are moving apart, and `f_c`
//! is the carrier frequency. The relativistic correction is below
//! the ppm range at any velocity a Minecraft entity will reach, so
//! we skip it.
//!
//! Sign convention: positive return value means the receiver sees a
//! frequency *higher* than the transmitter emits, which corresponds
//! to the two converging. This is the opposite sign of the textbook
//! `v_r` because the textbook defines `v_r` positive for receding;
//! we flip the sign inside the function so callers can pass the
//! literal "approaching velocity" they computed.

use crate::propagation::friis::SPEED_OF_LIGHT_M_S;

/// Doppler shift in hertz.
///
/// `approach_velocity_m_s` is positive when the radial distance is
/// shrinking. `carrier_hz` is the transmitter centre frequency.
#[inline]
pub fn doppler_shift_hz(approach_velocity_m_s: f32, carrier_hz: f64) -> f32 {
    // Keep the carrier in f64 through the multiply. A VHF carrier such
    // as 145 MHz sits above the 2^24 limit where f32 can represent
    // every integer hertz, so casting it to f32 first would quantise it
    // to the nearest 16 Hz before the shift is even computed. The ratio
    // v/c is tiny, so forming carrier_hz * v / c in f64 and casting only
    // the small result keeps full precision; the Doppler shift itself
    // fits f32 comfortably.
    let v = approach_velocity_m_s as f64;
    let c = SPEED_OF_LIGHT_M_S as f64;
    (carrier_hz * v / c) as f32
}

/// Radial component of the relative velocity along the line of
/// sight. Helper for callers that already have full 3D velocities.
pub fn radial_approach_velocity(
    tx_pos: [f32; 3],
    rx_pos: [f32; 3],
    tx_vel: [f32; 3],
    rx_vel: [f32; 3],
) -> f32 {
    let dx = rx_pos[0] - tx_pos[0];
    let dy = rx_pos[1] - tx_pos[1];
    let dz = rx_pos[2] - tx_pos[2];
    let dist = (dx * dx + dy * dy + dz * dz).sqrt();
    if dist <= 0.0 {
        return 0.0;
    }
    let inv = 1.0 / dist;
    let ux = dx * inv;
    let uy = dy * inv;
    let uz = dz * inv;
    let relative = [tx_vel[0] - rx_vel[0], tx_vel[1] - rx_vel[1], tx_vel[2] - rx_vel[2]];
    relative[0] * ux + relative[1] * uy + relative[2] * uz
}