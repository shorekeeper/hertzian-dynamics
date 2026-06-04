//! Voxel grid ABI. Exposes ChunkedVoxelGrid, the production
//! sparse grid. The dense variant is internal to rf-core tests.

use std::os::raw::c_void;

use rf_core::propagation::material::MaterialId;
use rf_core::propagation::voxel::ChunkedVoxelGrid;

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[no_mangle]
pub extern "C" fn hd_grid_create(voxel_size_m: f32, out_handle: *mut *mut c_void) -> i32 {
    safe_call(|| {
        if out_handle.is_null() {
            return HD_ERR_NULL;
        }
        if !(voxel_size_m > 0.0 && voxel_size_m.is_finite()) {
            return HD_ERR_INVALID_ARG;
        }
        let grid = ChunkedVoxelGrid::new(voxel_size_m);
        // SAFETY: out_handle is non-null and aligned by contract.
        unsafe { *out_handle = handle::into_raw(grid) };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_grid_destroy(handle_ptr: *mut c_void) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        match unsafe { handle::from_raw_owned::<ChunkedVoxelGrid>(handle_ptr) } {
            Ok(b) => {
                drop(b);
                HD_OK
            }
            Err(()) => HD_ERR_NULL,
        }
    })
}

#[no_mangle]
pub extern "C" fn hd_grid_set_voxel(
    handle_ptr: *mut c_void,
    x: i32,
    y: i32,
    z: i32,
    material_id: u16,
) -> i32 {
    safe_call(|| {
        // SAFETY: see hd_core_destroy.
        let grid = match unsafe { handle::as_mut::<ChunkedVoxelGrid>(handle_ptr) } {
            Some(g) => g,
            None => return HD_ERR_NULL,
        };
        grid.set(x, y, z, MaterialId(material_id));
        HD_OK
    })
}