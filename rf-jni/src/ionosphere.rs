//! Ionosphere LUT ABI.

use std::os::raw::c_void;

use rf_core::propagation::{IonosphereLut, SolarActivity};

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[no_mangle]
pub extern "C" fn hd_iono_create(activity: u32, out_handle: *mut *mut c_void) -> i32 {
    safe_call(|| {
        if out_handle.is_null() {
            return HD_ERR_NULL;
        }
        let sa = match activity {
            0 => SolarActivity::Low,
            1 => SolarActivity::Medium,
            2 => SolarActivity::High,
            _ => return HD_ERR_OUT_OF_RANGE,
        };
        let lut = IonosphereLut::for_activity(sa);
        // SAFETY: out_handle non-null.
        unsafe { *out_handle = handle::into_raw(lut) };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_iono_destroy(handle_ptr: *mut c_void) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        match unsafe { handle::from_raw_owned::<IonosphereLut>(handle_ptr) } {
            Ok(b) => {
                drop(b);
                HD_OK
            }
            Err(()) => HD_ERR_NULL,
        }
    })
}