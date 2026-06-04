//! Material table ABI.

use std::os::raw::c_void;

use rf_core::propagation::material::{Material, MaterialId, MaterialTable};

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[no_mangle]
pub extern "C" fn hd_materials_create_defaults(out_handle: *mut *mut c_void) -> i32 {
    safe_call(|| {
        if out_handle.is_null() {
            return HD_ERR_NULL;
        }
        let tab = MaterialTable::with_defaults();
        // SAFETY: out_handle non-null.
        unsafe { *out_handle = handle::into_raw(tab) };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_materials_destroy(handle_ptr: *mut c_void) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        match unsafe { handle::from_raw_owned::<MaterialTable>(handle_ptr) } {
            Ok(b) => {
                drop(b);
                HD_OK
            }
            Err(()) => HD_ERR_NULL,
        }
    })
}

#[no_mangle]
pub extern "C" fn hd_materials_register(
    handle_ptr: *mut c_void,
    material_id: u16,
    atten_db_per_m_at_ref: f32,
    reference_frequency_hz: f32,
    scaling_exponent: f32,
    pivot_frequency_hz: f32,
) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        let tab = match unsafe { handle::as_mut::<MaterialTable>(handle_ptr) } {
            Some(t) => t,
            None => return HD_ERR_NULL,
        };
        if !atten_db_per_m_at_ref.is_finite()
            || !reference_frequency_hz.is_finite()
            || !scaling_exponent.is_finite()
            || !pivot_frequency_hz.is_finite()
        {
            return HD_ERR_INVALID_ARG;
        }
        if reference_frequency_hz <= 0.0 || pivot_frequency_hz <= 0.0 {
            return HD_ERR_INVALID_ARG;
        }
        // Slot name is informational; the Java side passes a
        // pre-registered numeric id and does not need the string.
        tab.register(Material {
            id: MaterialId(material_id),
            name: "user",
            atten_db_per_m_at_ref,
            reference_frequency_hz,
            scaling_exponent,
            pivot_frequency_hz,
        });
        HD_OK
    })
}