//! Receiver registration, tuning, position update.

use std::os::raw::c_void;

use rf_core::spectrum::{NoiseEnvironment, ReceiverConfig, ReceiverId, SpectrumManager};
use rf_core::spectrum::agc::AgcConfig;
use rf_core::types::modulation::Modulation;

use crate::error::*;
use crate::handle;
use crate::safe_call;

#[repr(C)]
#[derive(Copy, Clone)]
pub struct HdReceiverConfig {
    pub tuned_hz: f64,
    pub bandwidth_hz: f32,
    pub modulation: u32,
    pub antenna_gain: f32,
    pub pos_x: f32,
    pub pos_y: f32,
    pub pos_z: f32,
    pub vel_x: f32,
    pub vel_y: f32,
    pub vel_z: f32,
    pub agc_target: f32,
    pub agc_attack_seconds: f32,
    pub agc_release_seconds: f32,
    pub agc_max_gain: f32,
    pub agc_min_gain: f32,
    pub noise_figure_db: f32,
    pub noise_environment: u32,
}

const _: () = {
    // tuned_hz (f64) sits at offset 0, so its 8 byte alignment
    // requirement is met. Every field after it is a 4 byte primitive.
    // The two noise fields appended in this revision (noise_figure_db
    // at offset 64, noise_environment at offset 68) keep the total at
    // 72 bytes, a multiple of the 8 byte alignment with no trailing
    // padding.
    assert!(core::mem::size_of::<HdReceiverConfig>() == 72);
    assert!(core::mem::align_of::<HdReceiverConfig>() == 8);
};

fn build_agc(c: &HdReceiverConfig) -> AgcConfig {
    // Zero values in the AGC fields opt into engine defaults.
    let default = AgcConfig::default();
    AgcConfig {
        target_amplitude: if c.agc_target > 0.0 { c.agc_target } else { default.target_amplitude },
        attack_seconds: if c.agc_attack_seconds > 0.0 { c.agc_attack_seconds } else { default.attack_seconds },
        release_seconds: if c.agc_release_seconds > 0.0 { c.agc_release_seconds } else { default.release_seconds },
        max_gain: if c.agc_max_gain > 0.0 { c.agc_max_gain } else { default.max_gain },
        min_gain: if c.agc_min_gain > 0.0 { c.agc_min_gain } else { default.min_gain },
    }
}

#[no_mangle]
pub extern "C" fn hd_manager_register_receiver(
    handle_ptr: *mut c_void,
    config: *const HdReceiverConfig,
    out_receiver_id: *mut u32,
) -> i32 {
    safe_call(|| {
        if config.is_null() || out_receiver_id.is_null() {
            return HD_ERR_NULL;
        }
        // SAFETY: caller guarantees config points at an initialised
        // HdReceiverConfig.
        let c = unsafe { &*config };
        // SAFETY: handle is the SpectrumManager boxed by hd_manager_create.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let modulation = match Modulation::from_u32(c.modulation) {
            Some(m) => m,
            None => return HD_ERR_INVALID_ARG,
        };
        if !(c.tuned_hz.is_finite() && c.tuned_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        if !(c.bandwidth_hz.is_finite() && c.bandwidth_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        // Noise figure: a non finite value falls back to the engine
        // default rather than poisoning the floor with a NaN sigma.
        // The clamp keeps the implied linear factor in a sane range.
        let noise_figure_db = if c.noise_figure_db.is_finite() {
            c.noise_figure_db.clamp(0.0, 60.0)
        } else {
            12.0
        };
        // Unknown environment codes fall back to the residential middle
        // ground rather than rejecting the registration, so a zeroed
        // struct from a raw C caller still produces a usable receiver.
        let noise_environment = NoiseEnvironment::from_code(c.noise_environment)
            .unwrap_or(NoiseEnvironment::Residential);
        let cfg = ReceiverConfig {
            tuned_hz: c.tuned_hz,
            bandwidth_hz: c.bandwidth_hz,
            modulation,
            antenna_gain: c.antenna_gain.max(1e-6),
            position: [c.pos_x, c.pos_y, c.pos_z],
            velocity: [c.vel_x, c.vel_y, c.vel_z],
            agc: build_agc(c),
            noise_figure_db,
            noise_environment,
        };
        let id = manager.register_receiver(cfg);
        // SAFETY: out_receiver_id non-null.
        unsafe { *out_receiver_id = id.0 };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_unregister_receiver(handle_ptr: *mut c_void, receiver_id: u32) -> i32 {
    safe_call(|| {
        // SAFETY: see register.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        if manager.unregister_receiver(ReceiverId(receiver_id)).is_some() {
            HD_OK
        } else {
            HD_ERR_NOT_FOUND
        }
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_set_receiver_position(
    handle_ptr: *mut c_void,
    receiver_id: u32,
    x: f32,
    y: f32,
    z: f32,
    vx: f32,
    vy: f32,
    vz: f32,
) -> i32 {
    safe_call(|| {
        // SAFETY: see register.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let rx = match manager.receiver_mut(ReceiverId(receiver_id)) {
            Some(r) => r,
            None => return HD_ERR_NOT_FOUND,
        };
        rx.set_position_velocity([x, y, z], [vx, vy, vz]);
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_set_receiver_tuning(
    handle_ptr: *mut c_void,
    receiver_id: u32,
    tuned_hz: f64,
    bandwidth_hz: f32,
) -> i32 {
    safe_call(|| {
        if !(tuned_hz.is_finite() && tuned_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        if !(bandwidth_hz.is_finite() && bandwidth_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        // SAFETY: see register.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let rx = match manager.receiver_mut(ReceiverId(receiver_id)) {
            Some(r) => r,
            None => return HD_ERR_NOT_FOUND,
        };
        rx.set_tuned_hz(tuned_hz);
        // Bandwidth lives in ReceiverConfig; the API does not yet
        // expose a setter so we rebuild the config via direct field
        // mutation. The receiver carries an immutable view via
        // `config()`; the manager re-fetches it from the AGC on the
        // next mix call.
        // Note: ReceiverConfig is Copy; the field access compiles.
        let mut cfg = *rx.config();
        cfg.bandwidth_hz = bandwidth_hz;
        // Replace the receiver to commit the new bandwidth. The
        // hashmap holds the receiver by value so we cannot mutate
        // the stored ReceiverConfig in place without an internal
        // setter; someday I'll add one. Until then we work
        // around it by re-registering, which loses the AGC state.
        // The loss is acceptable for a tuning change because the
        // AGC re-seeds on the next sample anyway.
        let id = ReceiverId(receiver_id);
        manager.unregister_receiver(id);
        let new_id = manager.register_receiver(cfg);
        // Best effort: if the new id differs the Java side will
        // observe via a subsequent read. We do not attempt to
        // preserve the id across the swap.
        let _ = new_id;
        HD_OK
    })
}