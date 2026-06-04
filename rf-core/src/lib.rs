//! # rf-core
//!
//! GPU accelerated RF propagation and DSP core for the Hertzian Dynamics
//! Minecraft mod. The crate is headless: it knows nothing about
//! Minecraft, Forge, JNI or audio output.
//!
//! ## Layered modules
//!
//! * [`error`]       unified `RfCoreError` and `Result` alias.
//! * [`types`]       plain data shared between CPU and GPU, plus the
//!                   propagation result types.
//! * [`vulkan`]      Vulkan plumbing on top of `ash`.
//! * [`dsp`]         signal processing primitives.
//! * [`compute`]     backend selection between CPU and GPU per workload.
//! * [`propagation`] free space loss, voxel absorption, knife edge
//!                   diffraction, ionospheric skywave, Rayleigh fading,
//!                   Doppler shift, composed into `PropagationSolver`.
//! * [`spectrum`]    spectrum manager and interference mixer.
//! * [`io`]          WAV and CSV writers for diagnostics.
//! * [`context`]     `RfCore` facade.

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_docs)]

pub mod error;
pub mod types;
pub mod vulkan;
pub mod dsp;
pub mod compute;
pub mod propagation;
pub mod spectrum;
pub mod io;
pub mod context;

pub use compute::{
    pack_query, ComputeBackend, ComputeContext, ComputePolicy, ComputeStats, ComputeWorkload,
    QUERY_STRIDE,
};
pub use context::{RfCore, RfCoreConfig};
pub use error::{Result, RfCoreError};
pub use types::{
    emission::{Emission, EmissionId},
    iq::Iq,
    modulation::Modulation,
    propagation::{LossBreakdown, PathLoss},
    spectrum::{SpectrumChunk, SpectrumChunkHeader},
};