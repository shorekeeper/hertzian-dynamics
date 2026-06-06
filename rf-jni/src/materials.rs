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

/// Register or overwrite a material slot from its electrical properties.
/// The four power law coefficients are epsilon_r = eps_a * f_GHz^eps_b and
/// sigma = sigma_c * f_GHz^sigma_d in siemens per metre; pivot_frequency_hz
/// is the lower clamp on the property evaluation frequency. See
/// rf_core::propagation::material for the model.
#[no_mangle]
pub extern "C" fn hd_materials_register(
    handle_ptr: *mut c_void,
    material_id: u16,
    eps_a: f32,
    eps_b: f32,
    sigma_c: f32,
    sigma_d: f32,
    pivot_frequency_hz: f32,
) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        let tab = match unsafe { handle::as_mut::<MaterialTable>(handle_ptr) } {
            Some(t) => t,
            None => return HD_ERR_NULL,
        };
        if !eps_a.is_finite()
            || !eps_b.is_finite()
            || !sigma_c.is_finite()
            || !sigma_d.is_finite()
            || !pivot_frequency_hz.is_finite()
        {
            return HD_ERR_INVALID_ARG;
        }
        if eps_a <= 0.0 || sigma_c < 0.0 || pivot_frequency_hz <= 0.0 {
            return HD_ERR_INVALID_ARG;
        }
        // Slot name is informational; the Java side passes a pre-registered
        // numeric id and does not need the string.
        tab.register(Material {
            id: MaterialId(material_id),
            name: "user",
            eps_a,
            eps_b,
            sigma_c,
            sigma_d,
            pivot_frequency_hz,
        });
        HD_OK
    })
}