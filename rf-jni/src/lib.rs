//! C ABI surface for `rf-core`, consumed by the Hertzian Dynamics
//! Java mod through the FFM API.
//!
//! Architecture
//! ------------
//!
//! Every exported function:
//!   * is `#[no_mangle] pub extern "C"`;
//!   * returns `i32` (zero is success, negative is failure);
//!   * uses out-pointer parameters for any data it produces;
//!   * is wrapped in `catch_unwind` so a Rust panic never crosses
//!     the FFI boundary.
//!
//! Handles
//! -------
//!
//! Each long lived object (RfCore, SpectrumManager, ChunkedVoxelGrid,
//! MaterialTable, IonosphereLut) is boxed and exposed as a raw
//! pointer. The Java side stores the pointer in a `MemorySegment`
//! of `ValueLayout.ADDRESS` size and hands it back unchanged. The
//! destructor restores the box and drops it.
//!
//! Versioning
//! ----------
//!
//! `HD_ABI_VERSION` is bumped on any breaking change to the ABI.
//! Callers should query `hd_abi_version` first and refuse to load
//! if the value differs from what they were compiled against.
//!
//! Re-export layout
//! ----------------
//!
//! The crate is organised internally as one module per ABI surface
//! (core lifecycle, grid, materials, ionosphere, manager,
//! emission, receiver, mix). Every public `extern "C"` function is
//! re-exported flat from the crate root so consumers of the rlib
//! (notably the integration tests in `tests/`) can write
//! `rf_jni::hd_manager_create` without threading the module path
//! through. The C symbol layer is already flat - `#[no_mangle]`
//! puts every function in one linker namespace - so flattening
//! the Rust surface keeps the two views consistent.

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_docs)]

pub mod error;
pub mod handle;
pub mod core;
pub mod grid;
pub mod materials;
pub mod ionosphere;
pub mod manager;
pub mod emission;
pub mod receiver;
pub mod mix;
pub mod zoom_dft;
pub mod compute;
pub mod raycast;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::os::raw::c_char;

pub use error::*;

// Flat re-exports of every exported function. Submodules retain the
// module path for organisation; the crate root mirrors the C side
// where everything lives in one symbol table.
//
// When adding a new `extern "C"` function, add it to its module and
// to the matching `pub use` line below. Test files (and downstream
// rlib consumers, if any appear) need no further changes.
pub use crate::core::{hd_core_create, hd_core_create_ex, hd_core_destroy};
pub use crate::raycast::hd_raycast_batch;
pub use crate::compute::{hd_core_compute_stats, hd_core_set_compute_policy};
pub use crate::zoom_dft::hd_zoom_dft;
pub use crate::grid::{hd_grid_create, hd_grid_destroy, hd_grid_set_voxel};
pub use crate::materials::{
    hd_materials_create_defaults, hd_materials_destroy, hd_materials_register,
};
pub use crate::ionosphere::{hd_iono_create, hd_iono_destroy};
pub use crate::manager::{hd_manager_create, hd_manager_destroy, hd_manager_set_curvature};
pub use crate::emission::{
    hd_manager_push_audio, hd_manager_register_emission, hd_manager_set_emission_position,
    hd_manager_unregister_emission,
};
pub use crate::receiver::{
    hd_manager_register_receiver, hd_manager_set_receiver_position,
    hd_manager_set_receiver_tuning, hd_manager_unregister_receiver,
};
pub use crate::mix::hd_manager_mix_chunk;

/// ABI revision. Bumped on every breaking change to any exported
/// function or struct layout.
pub const HD_ABI_VERSION: u32 = 1;

/// Library version string, NUL terminated. Lives in static memory.
const HD_VERSION_C: &[u8] = concat!(env!("CARGO_PKG_VERSION"), "\0").as_bytes();

/// Probe the ABI version. The Java side calls this immediately
/// after loading the library and bails if the value does not
/// match what it was compiled against.
#[no_mangle]
pub extern "C" fn hd_abi_version(out_version: *mut u32) -> i32 {
    safe_call(|| {
        if out_version.is_null() {
            return HD_ERR_NULL;
        }
        // SAFETY: out_version was checked above; the caller is
        // responsible for the pointee being aligned and writable.
        unsafe { *out_version = HD_ABI_VERSION };
        HD_OK
    })
}

/// Return a pointer to the library version string. The string is
/// owned by the library and remains valid for the lifetime of the
/// process. Never returns NULL.
#[no_mangle]
pub extern "C" fn hd_version_string() -> *const c_char {
    HD_VERSION_C.as_ptr() as *const c_char
}

/// Wrap an exported function body. Converts panics to
/// `HD_ERR_PANIC`. Any return value other than success/error code
/// goes through an out parameter, so the wrapper signature is
/// always `i32`.
///
/// `AssertUnwindSafe` is applied because the closures here only
/// touch FFI-friendly types whose Drop impls are themselves safe
/// to run during unwind. If a future closure violates this, the
/// wrap site must be updated explicitly.
pub(crate) fn safe_call<F>(f: F) -> i32
where
    F: FnOnce() -> i32,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => HD_ERR_PANIC,
    }
}