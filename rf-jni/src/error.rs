//! Error code constants exposed across the C ABI.
//!
//! Codes are negative so callers can use `if (rc) { ... }` style
//! checks for success without ambiguity. Add new codes by
//! decrementing; never reuse a value.

use rf_core::RfCoreError;

/// Success.
pub const HD_OK: i32 = 0;
/// A required pointer argument was NULL.
pub const HD_ERR_NULL: i32 = -1;
/// An argument failed validation (range, alignment, etc).
pub const HD_ERR_INVALID_ARG: i32 = -2;
/// A numeric argument was outside the supported range.
pub const HD_ERR_OUT_OF_RANGE: i32 = -3;
/// A handle or id did not resolve.
pub const HD_ERR_NOT_FOUND: i32 = -4;
/// A Vulkan call inside rf-core returned a failure code.
pub const HD_ERR_VK_FAILED: i32 = -5;
/// SPIR-V validation rejected a shader module.
pub const HD_ERR_INVALID_SHADER: i32 = -6;
/// State machine violation: an operation was attempted in the wrong
/// order, for example mixing before any emission is registered.
pub const HD_ERR_INVALID_STATE: i32 = -7;
/// A Rust panic was caught and contained at the FFI boundary.
pub const HD_ERR_PANIC: i32 = -100;
/// Catch-all for unmapped errors.
pub const HD_ERR_UNKNOWN: i32 = -999;

/// Translate an `rf_core::RfCoreError` into a stable error code.
/// The mapping is intentionally coarse: the caller learns what
/// kind of failure happened but not the specific message. Detailed
/// diagnostics live in the Rust logs; the Java side surfaces the
/// integer code in its exception message.
///
/// The match arm for unknown variants is mandatory because
/// `RfCoreError` is declared `#[non_exhaustive]`. New variants
/// added to rf-core in future versions land in `HD_ERR_UNKNOWN`
/// until this mapping is updated; the JNI layer thus stays
/// compile-clean across non-breaking rf-core changes.
pub fn map_core_error(e: &RfCoreError) -> i32 {
    match e {
        RfCoreError::LoaderLoad(_) => HD_ERR_VK_FAILED,
        RfCoreError::Vulkan(_, _) => HD_ERR_VK_FAILED,
        RfCoreError::NoSuitableDevice => HD_ERR_VK_FAILED,
        RfCoreError::InvalidShader(_) => HD_ERR_INVALID_SHADER,
        RfCoreError::NoMemoryType => HD_ERR_VK_FAILED,
        RfCoreError::InvalidArgument(_) => HD_ERR_INVALID_ARG,
        _ => HD_ERR_UNKNOWN,
    }
}