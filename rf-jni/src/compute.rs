//! Compute backend configuration FFI.
//!
//! Sets the per-workload backend policy and reads back the dispatch
//! counters. The policy mirrors rf_core::compute::ComputePolicy: mode 0
//! Auto with a work threshold, mode 1 force CPU, mode 2 force GPU. Forcing
//! GPU when no GPU path exists falls back to CPU at call time.

use std::os::raw::c_void;

use rf_core::compute::{ComputePolicy, ComputeStats, ComputeWorkload};
use rf_core::RfCore;

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[no_mangle]
pub extern "C" fn hd_core_set_compute_policy(
    core_ptr: *mut c_void,
    workload: u32,
    mode: u32,
    min_work: u64,
) -> i32 {
    safe_call(|| {
        // SAFETY: core_ptr is the boxed RfCore from a core create function.
        let core = match unsafe { handle::as_mut::<RfCore>(core_ptr) } {
            Some(c) => c,
            None => return HD_ERR_NULL,
        };
        let wl = match ComputeWorkload::from_u32(workload) {
            Some(w) => w,
            None => return HD_ERR_INVALID_ARG,
        };
        if mode > 2 {
            return HD_ERR_INVALID_ARG;
        }
        core.set_compute_policy(wl, ComputePolicy::from_mode(mode, min_work));
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_core_compute_stats(
    core_ptr: *mut c_void,
    workload: u32,
    out_stats: *mut ComputeStats,
) -> i32 {
    safe_call(|| {
        if out_stats.is_null() {
            return HD_ERR_NULL;
        }
        // SAFETY: see set_compute_policy; read only borrow here.
        let core = match unsafe { handle::as_ref::<RfCore>(core_ptr) } {
            Some(c) => c,
            None => return HD_ERR_NULL,
        };
        let wl = match ComputeWorkload::from_u32(workload) {
            Some(w) => w,
            None => return HD_ERR_INVALID_ARG,
        };
        let stats = core.compute_stats(wl);
        // SAFETY: out_stats non-null and the caller guarantees it points at
        // a writable hd_compute_stats_t, which ComputeStats mirrors.
        unsafe { *out_stats = stats };
        HD_OK
    })
}