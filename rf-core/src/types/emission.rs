//! Emission descriptor: metadata of one active transmitter.
//!
//! Payload samples (audio for AM and FM, key states for CW) live in
//! ring buffers owned by the spectrum manager and are
//! referenced by `EmissionId`. This struct carries only the geometry
//! and RF parameters the propagation solver needs to decide whether
//! the emission reaches a given receiver and how to mix it in.

use crate::types::modulation::Modulation;

/// Opaque identifier of an emission. Stable for the lifetime of the
/// emission, recycled after it ends. The spectrum manager assigns
/// these monotonically and reuses freed slots only after a safety
/// margin to keep stale references obvious.
#[repr(transparent)]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct EmissionId(pub u32);

/// CPU side emission descriptor.
///
/// The carrier frequency is kept as `f64` because rf-core covers
/// roughly ten decades from VLF beacons up to VHF FM broadcast, and
/// the difference between a 100 MHz carrier and a 100 MHz + 1 kHz
/// neighbour does not survive a round trip through `f32`. The GPU
/// counterpart introduced in code will store carrier as
/// `f32` relative to a per batch reference frequency to keep shader
/// math in single precision.
///
/// Positions are world space metres, expressed as `f32`. The
/// effective propagation range tops out around ten thousand metres
/// for the strongest HF skywave path, which fits comfortably in
/// `f32` precision. The caller anchors absolute world coordinates
/// to a local origin before constructing the descriptor.
#[derive(Copy, Clone, Debug)]
pub struct Emission {
    /// Stable identifier assigned by the spectrum manager.
    pub id: EmissionId,
    /// Modulation mode.
    pub modulation: Modulation,
    /// World space x coordinate, metres.
    pub pos_x: f32,
    /// World space y coordinate, metres.
    pub pos_y: f32,
    /// World space z coordinate, metres.
    pub pos_z: f32,
    /// Transmitter conducted power in watts, before antenna gain.
    pub tx_power_w: f32,
    /// Antenna gain over isotropic, linear scale (not dB). A short
    /// telescopic whip is around 1.0, a six element Yagi roughly 10.
    pub antenna_gain: f32,
    /// Carrier frequency in hertz.
    pub carrier_hz: f64,
    /// Occupied RF bandwidth, full width at the channel mask edges,
    /// in hertz.
    pub bandwidth_hz: f32,
}