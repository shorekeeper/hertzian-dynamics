//! Spectrum manager and interference mixer.
//!
//! This module owns the shared radio ether. Transmitters
//! ("emissions") publish modulated baseband samples into a single
//! manager; receivers ("tuners") request mixed baseband chunks
//! tuned to their centre frequency and filter bandwidth. The mixer
//! sums every reachable emission, applies a per-pair frequency
//! offset to translate each emission into the receiver passband,
//! injects a thermal noise floor and runs the result through a
//! per-receiver AGC.
//!
//! Layering
//! --------
//!
//! * [`ring`]     fixed capacity audio ring buffer for PCM input.
//! * [`agc`]      attack/release AGC operating on Iq samples.
//! * [`emission`] active transmitter state, owns one modulator and
//!                one audio source.
//! * [`receiver`] receiver state, owns the tuning, the AGC, and the
//!                per-emission phase accumulators that keep the
//!                frequency offset NCOs continuous across chunks.
//! * [`mixer`]    combines emissions into a `SpectrumChunk` for one
//!                receiver, asking the `PropagationSolver` for path
//!                losses.
//! * [`manager`]  registers emissions and receivers, drives audio
//!                ingestion and dispatches mix calls.
//!
//!
//! Sample rate
//! -----------
//!
//! Every emission and every receiver runs at the same engine sample
//! rate.
//! Voice grade AM, NFM and SSB all fit comfortably in 48 kHz, and a
//! single rate avoids per-pair resampler state. Broadcast FM at
//! 200 kHz channel spacing is modelled as a narrower 15 kHz
//! deviation NFM, which is the same approximation noted in the
//! earlier architecture document.

pub mod ring;
pub mod agc;
pub mod emission;
pub mod receiver;
pub mod mixer;
pub mod manager;
pub mod noise_floor;

pub use agc::{Agc, AgcConfig};
pub use emission::{ActiveEmission, EmissionContent, EmissionModulator, EmissionModulatorKind};
pub use manager::{ReceiverId, SpectrumManager, SpectrumManagerConfig};
pub use mixer::InterferenceMixer;
pub use noise_floor::NoiseEnvironment;
pub use receiver::{Receiver, ReceiverConfig};
pub use ring::AudioRing;

/// Engine-wide sample rate, in hertz. Every emission and every
/// receiver runs at this rate. Changing it is allowed at startup
/// before any emissions or receivers are registered; once they
/// exist, the rate is frozen.
pub const ENGINE_SAMPLE_RATE_HZ: f32 = 48_000.0;

/// Thermal noise power spectral density at the reference
/// temperature, in watts per hertz. This is k*T0 with the Boltzmann
/// constant k = 1.380649e-23 J/K and the IEEE standard noise
/// reference temperature T0 = 290 K, which evaluates to about
/// 4.004e-21 W/Hz, the canonical -174 dBm/Hz floor.
///
/// The mixer obtains the operating noise power by multiplying this
/// density by the receiver bandwidth and the total system noise
/// factor from `noise_floor::system_noise_factor`. Expressing the
/// floor as a density removes the earlier hard-coded 15 kHz reference
/// bandwidth: the noise now scales with the receiver's actual
/// bandwidth directly, which is the physical behaviour (a wider filter
/// collects proportionally more noise power).
///
/// History: an earlier versions of my code, I was setting a single THERMAL_NOISE_POWER_W
/// constant to 1e-12 W, roughly 42 dB above the real thermal floor, as
/// a blunt balance knob. That made the signal to noise ratio a tuned
/// number rather than a physical one and left the spectrum analyzer's
/// absolute dB axis meaningless. The density plus the per-receiver
/// noise factor restores a physical floor while the noise figure and
/// environment fields keep it tunable in a way that maps onto real
/// receiver and site characteristics.
pub const THERMAL_NOISE_DENSITY_W_PER_HZ: f32 = 4.004e-21;