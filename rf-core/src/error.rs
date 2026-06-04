//! Single unified error type for the crate.
//!
//! No external error crate is used.

use std::fmt;

/// Result alias used across the crate.
pub type Result<T> = std::result::Result<T, RfCoreError>;

/// Top level error type.
#[non_exhaustive]
#[derive(Debug)]
pub enum RfCoreError {
    /// Failed to dlopen the Vulkan loader. String carries the OS
    /// specific message returned by libloading.
    LoaderLoad(String),
    /// A Vulkan API call returned a non success result code. First
    /// field is the static name of the call site, second is the raw
    /// vk::Result rendered to text.
    Vulkan(&'static str, String),
    /// No physical device satisfied the engine requirements
    /// (compute queue family at minimum).
    NoSuitableDevice,
    /// SPIR-V bytes did not pass shallow validation, for example
    /// because their length is not a multiple of four.
    InvalidShader(&'static str),
    /// No Vulkan memory type matched the requested usage flags.
    NoMemoryType,
    /// Caller invariant violated. The static string explains.
    InvalidArgument(&'static str),
}

impl fmt::Display for RfCoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::LoaderLoad(s) => write!(f, "vulkan loader load failed: {s}"),
            Self::Vulkan(ctx, code) => write!(f, "vulkan call '{ctx}' failed: {code}"),
            Self::NoSuitableDevice => write!(f, "no Vulkan device with required features"),
            Self::InvalidShader(why) => write!(f, "invalid SPIR-V module: {why}"),
            Self::NoMemoryType => write!(f, "no compatible Vulkan memory type"),
            Self::InvalidArgument(why) => write!(f, "invalid argument: {why}"),
        }
    }
}

impl std::error::Error for RfCoreError {}

/// Wrap a raw vk::Result into a RfCoreError tagged with a call site.
/// Submodules use this so they do not each reimplement the mapping.
pub(crate) fn vk_err(ctx: &'static str, code: ash::vk::Result) -> RfCoreError {
    RfCoreError::Vulkan(ctx, format!("{code:?}"))
}