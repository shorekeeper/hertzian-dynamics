//! Zoom DFT FFI.
//!
//! Bridges the analyzer's heavy DFT to rf-core, which runs it on the GPU
//! when available and on the CPU otherwise. The Java side passes the
//! receiver baseband chunk as interleaved I/Q floats and receives per-bin
//! magnitude in dB. The window, projection, normalisation and dB
//! conversion all happen inside rf-core; the caller keeps only the
//! stateful smoothing.

use std::os::raw::c_void;

use rf_core::types::iq::Iq;
use rf_core::RfCore;

use crate::error::*;
use crate::handle;
use crate::safe_call;

/// Run the zoom DFT.
///
/// `samples` points at `2 * sample_count` interleaved f32 values
/// (I, Q, I, Q, ...). `out_db` receives `bins` f32 values. `span_hz` is
/// the analysis span centered on baseband DC; `fs_hz` is the engine
/// sample rate; `bins` is the output bin count.
#[no_mangle]
pub extern "C" fn hd_zoom_dft(
    core_ptr: *mut c_void,
    samples: *const f32,
    sample_count: u32,
    span_hz: f32,
    fs_hz: f32,
    bins: u32,
    out_db: *mut f32,
) -> i32 {
    safe_call(|| {
        if samples.is_null() || out_db.is_null() {
            return HD_ERR_NULL;
        }
        if sample_count == 0 || bins == 0 {
            return HD_ERR_INVALID_ARG;
        }
        if !(span_hz.is_finite() && span_hz > 0.0 && fs_hz.is_finite() && fs_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        // SAFETY: core_ptr is the boxed RfCore produced by a core create
        // function. The caller holds it alive for the duration of the call.
        let core = match unsafe { handle::as_mut::<RfCore>(core_ptr) } {
            Some(c) => c,
            None => return HD_ERR_NULL,
        };

        let n = sample_count as usize;
        let b = bins as usize;
        // SAFETY: the caller guarantees `samples` points at 2*n initialised
        // f32 values. Iq is repr(C) of two f32 with 4 byte alignment, so an
        // interleaved f32 array of length 2*n is exactly n Iq values.
        let iq = unsafe { std::slice::from_raw_parts(samples as *const Iq, n) };
        // SAFETY: the caller guarantees `out_db` points at b writable f32.
        let out = unsafe { std::slice::from_raw_parts_mut(out_db, b) };

        core.zoom_dft(iq, n, span_hz, fs_hz, b, out);
        HD_OK
    })
}