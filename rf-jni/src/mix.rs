//! Mix one chunk into a caller-provided buffer.

use std::os::raw::c_void;

use rf_core::propagation::{IonosphereLut, MaterialTable};
use rf_core::propagation::voxel::ChunkedVoxelGrid;
use rf_core::spectrum::{ReceiverId, SpectrumManager};
use rf_core::types::iq::Iq;
use rf_core::types::spectrum::{SpectrumChunk, SpectrumChunkHeader};

use crate::error::*;
use crate::handle;
use crate::safe_call;

/// C-side mirror of `hd_chunk_header_t`. The trailing field carries the
/// pre-AGC received signal power; paired with noise_floor_w it gives the
/// client S-meter a real signal to noise ratio. It occupies the slot that
/// used to be an explicit padding word, so the 40 byte layout and the
/// static size assertion against the Rust header are unchanged.
#[repr(C)]
#[derive(Copy, Clone)]
pub struct HdChunkHeader {
    pub center_hz: f64,
    pub sample_rate_hz: f32,
    pub bandwidth_hz: f32,
    pub sample_count: u32,
    pub sequence: u32,
    pub server_tick: u64,
    pub noise_floor_w: f32,
    pub signal_power_w: f32,
}

const _: () = {
    assert!(core::mem::size_of::<HdChunkHeader>() == core::mem::size_of::<SpectrumChunkHeader>());
};

#[no_mangle]
pub extern "C" fn hd_manager_mix_chunk(
    manager_ptr: *mut c_void,
    grid_ptr: *mut c_void,
    materials_ptr: *mut c_void,
    iono_ptr: *mut c_void,
    receiver_id: u32,
    sample_count: u32,
    server_tick: u64,
    local_hour: f32,
    out_header: *mut HdChunkHeader,
    out_samples: *mut Iq,
) -> i32 {
    safe_call(|| {
        if out_header.is_null() || out_samples.is_null() {
            return HD_ERR_NULL;
        }
        if sample_count == 0 {
            return HD_ERR_INVALID_ARG;
        }

        // SAFETY: every handle is the boxed type produced by the matching
        // create function. The caller's contract pins them alive for the
        // duration of this call.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(manager_ptr) } {
            Some(m) => m,
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
        let iono = match unsafe { handle::as_ref::<IonosphereLut>(iono_ptr) } {
            Some(i) => i,
            None => return HD_ERR_NULL,
        };

        let sample_rate_hz = manager.sample_rate_hz();
        let bandwidth_hz = manager
            .receiver(ReceiverId(receiver_id))
            .map(|r| r.bandwidth_hz())
            .unwrap_or(15_000.0);
        let header = SpectrumChunkHeader {
            center_hz: 0.0,
            sample_rate_hz,
            bandwidth_hz,
            sample_count,
            sequence: 0,
            server_tick,
            noise_floor_w: 0.0,
            signal_power_w: 0.0,
        };
        let mut chunk = SpectrumChunk::allocate(header);

        // The solver configuration, including the Earth curvature knobs,
        // lives on the manager and is set once per world from the FFI
        // curvature setter; pull it here so every mix uses it.
        let cfg = manager.solver_config();
        let ok = manager.mix_chunk(
            ReceiverId(receiver_id),
            grid,
            materials,
            iono,
            cfg,
            &mut chunk,
            server_tick,
            local_hour,
        );
        if !ok {
            return HD_ERR_NOT_FOUND;
        }

        // Copy out. SAFETY: out_samples points at sample_count Iq slots by
        // the caller's contract. HdChunkHeader and SpectrumChunkHeader
        // have identical repr(C) layout per the const assert above.
        let n = sample_count as usize;
        unsafe {
            std::ptr::copy_nonoverlapping(chunk.samples.as_ptr(), out_samples, n);
            *out_header = *(&chunk.header as *const SpectrumChunkHeader as *const HdChunkHeader);
        }
        HD_OK
    })
}