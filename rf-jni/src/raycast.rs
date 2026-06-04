//! Batched raycast FFI.

use std::os::raw::c_void;

use rf_core::propagation::material::MaterialTable;
use rf_core::propagation::voxel::ChunkedVoxelGrid;
use rf_core::RfCore;

use crate::error::*;
use crate::handle;
use crate::safe_call;

const QUERY_STRIDE: usize = 8;

#[no_mangle]
pub extern "C" fn hd_raycast_batch(
    core_ptr: *mut c_void,
    grid_ptr: *mut c_void,
    materials_ptr: *mut c_void,
    queries: *const f32,
    query_count: u32,
    frequency_hz: f64,
    budget_db: f32,
    out_db: *mut f32,
) -> i32 {
    safe_call(|| {
        if queries.is_null() || out_db.is_null() {
            return HD_ERR_NULL;
        }
        if query_count == 0 {
            return HD_ERR_INVALID_ARG;
        }
        if !(frequency_hz.is_finite() && frequency_hz > 0.0 && budget_db.is_finite() && budget_db > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        // SAFETY: handles are the boxed types from their create functions;
        // the caller holds them alive for the duration of the call.
        let core = match unsafe { handle::as_mut::<RfCore>(core_ptr) } {
            Some(c) => c,
            None => return HD_ERR_NULL,
        };
        let grid = match unsafe { handle::as_ref::<ChunkedVoxelGrid>(grid_ptr) } {
            Some(g) => g,
            None => return HD_ERR_NULL,
        };
        let materials = match unsafe { handle::as_ref::<MaterialTable>(materials_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };

        let count = query_count as usize;
        // SAFETY: caller guarantees queries points at count*8 f32 and out_db
        // at count f32.
        let q = unsafe { std::slice::from_raw_parts(queries, count * QUERY_STRIDE) };
        let out = unsafe { std::slice::from_raw_parts_mut(out_db, count) };

        core.raycast_batch(grid, materials, q, count, frequency_hz, budget_db, out);
        HD_OK
    })
}