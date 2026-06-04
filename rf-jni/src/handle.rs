//! Opaque handle helpers.
//!
//! Each long lived Rust object is boxed and exposed across the FFI
//! boundary as a `*mut c_void`. Two helpers cover the common cases:
//! `into_raw` boxes the value and returns the raw pointer, and
//! `from_raw_ref` / `from_raw_owned` recover it.
//!
//! All helpers are `unsafe` because the caller must guarantee the
//! pointer came from this crate's `into_raw` and was not subsequently
//! freed. Callers verify NULL up front and surface
//! `HD_ERR_NULL` when appropriate.

use std::os::raw::c_void;

/// Box `value` and return the raw pointer. The caller takes
/// ownership; pair every call with `from_raw_owned` to free.
pub fn into_raw<T>(value: T) -> *mut c_void {
    Box::into_raw(Box::new(value)) as *mut c_void
}

/// Reconstruct the box from a raw pointer and drop it. Used by
/// destructor functions. Returns Ok(()) on a non-null handle,
/// Err(()) otherwise so the caller can surface `HD_ERR_NULL`.
///
/// # Safety
///
/// `ptr` must have been produced by `into_raw` of the same type
/// `T`. Passing it twice or passing a different type is undefined.
pub unsafe fn from_raw_owned<T>(ptr: *mut c_void) -> Result<Box<T>, ()> {
    if ptr.is_null() {
        return Err(());
    }
    // SAFETY: documented in the function's safety contract.
    Ok(unsafe { Box::from_raw(ptr as *mut T) })
}

/// Borrow the boxed value behind a raw pointer.
///
/// # Safety
///
/// `ptr` must have been produced by `into_raw` of the same type
/// `T` and must outlive the returned reference.
pub unsafe fn as_ref<'a, T>(ptr: *mut c_void) -> Option<&'a T> {
    if ptr.is_null() {
        return None;
    }
    // SAFETY: see contract.
    Some(unsafe { &*(ptr as *const T) })
}

/// Mutably borrow the boxed value behind a raw pointer.
///
/// # Safety
///
/// Same as `as_ref` plus the usual aliasing rules for `&mut`.
pub unsafe fn as_mut<'a, T>(ptr: *mut c_void) -> Option<&'a mut T> {
    if ptr.is_null() {
        return None;
    }
    // SAFETY: see contract.
    Some(unsafe { &mut *(ptr as *mut T) })
}