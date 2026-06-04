//! Spectrum manager lifecycle.

use std::os::raw::c_void;

use rf_core::spectrum::{SpectrumManager, SpectrumManagerConfig};
use rf_core::RfCore;

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[no_mangle]
pub extern "C" fn hd_manager_create(core: *mut c_void, out_handle: *mut *mut c_void) -> i32 {
    safe_call(|| {
        if out_handle.is_null() {
            return HD_ERR_NULL;
        }
        // SAFETY: we use the core handle only to verify it is alive;
        // SpectrumManager does not currently store a borrow to it
        // because rf-core's manager is independent of the RfCore
        // facade. The Java side still threads the core through to
        // make a future migration to GPU-backed pieces seamless.
        let _ = match unsafe { handle::as_ref::<RfCore>(core) } {
            Some(c) => c,
            None => return HD_ERR_NULL,
        };
        let manager = SpectrumManager::new(SpectrumManagerConfig::default());
        // SAFETY: out_handle non-null.
        unsafe { *out_handle = handle::into_raw(manager) };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_destroy(handle_ptr: *mut c_void) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        match unsafe { handle::from_raw_owned::<SpectrumManager>(handle_ptr) } {
            Ok(b) => {
                drop(b);
                HD_OK
            }
            Err(()) => HD_ERR_NULL,
        }
    })
}

/// Set the Earth curvature parameters used by every later mix call.
/// `enabled` is a boolean (0 off, non zero on); the radius, k factor and
/// ground reference mirror the SolverConfig fields. A non finite or out
/// of range numeric leaves the corresponding field at its current value
/// so a caller can update one knob without disturbing the others.
#[no_mangle]
pub extern "C" fn hd_manager_set_curvature(
    handle_ptr: *mut c_void,
    enabled: i32,
    earth_radius_m: f32,
    k_factor: f32,
    ground_ref_m: f32,
) -> i32 {
    safe_call(|| {
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let mut cfg = manager.solver_config();
        cfg.model_curvature = enabled != 0;
        if earth_radius_m.is_finite() && earth_radius_m > 1000.0 {
            cfg.earth_radius_m = earth_radius_m;
        }
        if k_factor.is_finite() && k_factor > 0.1 {
            cfg.earth_k_factor = k_factor;
        }
        if ground_ref_m.is_finite() {
            cfg.curvature_ground_ref_m = ground_ref_m;
        }
        manager.set_solver_config(cfg);
        HD_OK
    })
}