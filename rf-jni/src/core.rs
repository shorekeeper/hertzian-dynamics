//! RfCore lifecycle.

use std::os::raw::c_void;

use rf_core::{RfCore, RfCoreConfig};

use crate::error::*;
use crate::handle;
use crate::safe_call;

/// Flag bit: build the GPU compute backend. Mirrors
/// HD_CORE_FLAG_ENABLE_GPU in rf_core.h.
const FLAG_ENABLE_GPU: u32 = 1;

/// Construct an RfCore with default configuration and GPU compute enabled.
/// Kept for callers that predate the flagged variant. Vulkan
/// initialisation can fail on hosts without a working ICD; in that case
/// `HD_ERR_VK_FAILED` is returned and `*out_handle` is left untouched.
#[no_mangle]
pub extern "C" fn hd_core_create(out_handle: *mut *mut c_void) -> i32 {
    hd_core_create_ex(FLAG_ENABLE_GPU, out_handle)
}

/// Construct an RfCore with explicit flags. `flags` bit 0 enables the GPU
/// compute backend; clearing it forces every workload onto the CPU.
/// GPU initialisation failure is not fatal and falls back to CPU.
#[no_mangle]
pub extern "C" fn hd_core_create_ex(flags: u32, out_handle: *mut *mut c_void) -> i32 {
    safe_call(|| {
        if out_handle.is_null() {
            return HD_ERR_NULL;
        }
        let mut config = RfCoreConfig::default();
        config.enable_gpu = (flags & FLAG_ENABLE_GPU) != 0;
        match RfCore::new(config) {
            Ok(core) => {
                let raw = handle::into_raw(core);
                // SAFETY: out_handle is non-null by the early return above;
                // the caller guarantees alignment.
                unsafe { *out_handle = raw };
                HD_OK
            }
            Err(e) => map_core_error(&e),
        }
    })
}

/// Drop an RfCore. After this call the handle is dangling.
#[no_mangle]
pub extern "C" fn hd_core_destroy(handle_ptr: *mut c_void) -> i32 {
    safe_call(|| {
        // SAFETY: handle_ptr is checked for null inside from_raw_owned and
        // the caller's contract states the pointer came from a core create
        // function with the same `RfCore` type.
        match unsafe { handle::from_raw_owned::<RfCore>(handle_ptr) } {
            Ok(boxed) => {
                drop(boxed);
                HD_OK
            }
            Err(()) => HD_ERR_NULL,
        }
    })
}