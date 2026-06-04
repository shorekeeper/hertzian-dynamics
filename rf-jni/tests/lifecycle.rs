//! ABI lifecycle: create, destroy, NULL handling. No mix yet.

use std::os::raw::c_void;
use std::ptr;

use rf_core::types::iq::Iq;
use rf_jni::*;

#[test]
fn abi_version_matches() {
    let mut v = 0u32;
    let rc = hd_abi_version(&mut v as *mut u32);
    assert_eq!(rc, HD_OK);
    assert_eq!(v, HD_ABI_VERSION);
}

#[test]
fn version_string_is_non_empty() {
    let p = hd_version_string();
    assert!(!p.is_null());
    let s = unsafe { std::ffi::CStr::from_ptr(p) };
    assert!(!s.to_bytes().is_empty());
}

#[test]
fn core_and_manager_round_trip() {
    let mut core: *mut c_void = ptr::null_mut();
    assert_eq!(hd_core_create(&mut core as *mut *mut c_void), HD_OK);
    assert!(!core.is_null());

    let mut mgr: *mut c_void = ptr::null_mut();
    assert_eq!(hd_manager_create(core, &mut mgr as *mut *mut c_void), HD_OK);
    assert!(!mgr.is_null());

    assert_eq!(hd_manager_destroy(mgr), HD_OK);
    assert_eq!(hd_core_destroy(core), HD_OK);
}

#[test]
fn destroy_on_null_returns_null_error() {
    assert_eq!(hd_core_destroy(ptr::null_mut()), HD_ERR_NULL);
    assert_eq!(hd_manager_destroy(ptr::null_mut()), HD_ERR_NULL);
    assert_eq!(hd_grid_destroy(ptr::null_mut()), HD_ERR_NULL);
    assert_eq!(hd_materials_destroy(ptr::null_mut()), HD_ERR_NULL);
    assert_eq!(hd_iono_destroy(ptr::null_mut()), HD_ERR_NULL);
}

#[test]
fn grid_set_voxel_then_destroy() {
    let mut g: *mut c_void = ptr::null_mut();
    assert_eq!(hd_grid_create(1.0, &mut g as *mut *mut c_void), HD_OK);
    assert_eq!(hd_grid_set_voxel(g, 10, 5, -3, 1), HD_OK);
    assert_eq!(hd_grid_destroy(g), HD_OK);
}

#[test]
fn materials_register_and_destroy() {
    let mut m: *mut c_void = ptr::null_mut();
    assert_eq!(hd_materials_create_defaults(&mut m as *mut *mut c_void), HD_OK);
    let rc = hd_materials_register(m, 8, 80.0, 100.0e6, 0.5, 30.0e6);
    assert_eq!(rc, HD_OK);
    assert_eq!(hd_materials_destroy(m), HD_OK);
}

#[test]
fn iono_activity_out_of_range_rejected() {
    let mut handle: *mut c_void = ptr::null_mut();
    assert_eq!(hd_iono_create(7, &mut handle as *mut *mut c_void), HD_ERR_OUT_OF_RANGE);
}

#[test]
fn iq_layout_matches_native_size() {
    assert_eq!(std::mem::size_of::<Iq>(), 8);
}