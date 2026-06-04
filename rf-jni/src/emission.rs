//! Emission registration, audio push, position update.

use std::os::raw::c_void;

use rf_core::dsp::modulation::{JamProfile, NoiseJammer};
use rf_core::spectrum::{ActiveEmission, EmissionModulatorKind, SpectrumManager, ENGINE_SAMPLE_RATE_HZ};
use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::modulation::Modulation;

use crate::error::*;
use crate::handle;
use crate::safe_call;

/// C-side mirror of `hd_emission_desc_t`. Layout must match the
/// header byte for byte. The struct is private to this module; the
/// header is the canonical declaration.
///
/// Field ordering rationale
/// ------------------------
///
/// `carrier_hz` is placed first so the 8-byte alignment requirement
/// of `f64` is satisfied at offset 0. Every subsequent field is
/// 4 bytes wide, which keeps the rest of the struct densely packed
/// without any compiler-inserted padding. Total size is 64 bytes
/// with no internal holes. Any future addition that introduces a
/// new 8-byte field must either land at an offset already aligned
/// to 8 or be paired with an explicit padding field; the static
/// assertion below catches a drift either way.
#[repr(C)]
#[derive(Copy, Clone)]
pub struct HdEmissionDesc {
    pub carrier_hz: f64,
    pub modulation: u32,
    pub bandwidth_hz: f32,
    pub pos_x: f32,
    pub pos_y: f32,
    pub pos_z: f32,
    pub vel_x: f32,
    pub vel_y: f32,
    pub vel_z: f32,
    pub tx_power_w: f32,
    pub antenna_gain: f32,
    pub pcm_capacity: u32,
    pub jam_profile: u32,
    pub jam_rate_hz: f32,
    pub jam_sigma: f32,
}

const _: () = {
    // Tight 64 byte layout. The first f64 absorbs the largest
    // alignment requirement; everything else is 4 byte primitives.
    assert!(core::mem::size_of::<HdEmissionDesc>() == 64);
    assert!(core::mem::align_of::<HdEmissionDesc>() == 8);
};

fn parse_modulation(value: u32) -> Option<Modulation> {
    Modulation::from_u32(value)
}

fn modulator_kind_for(value: u32) -> Option<EmissionModulatorKind> {
    Some(match parse_modulation(value)? {
        Modulation::Cw => EmissionModulatorKind::Cw,
        Modulation::Am => EmissionModulatorKind::Am,
        Modulation::NFm => EmissionModulatorKind::NarrowFm,
        Modulation::WFm => EmissionModulatorKind::WideFm,
        Modulation::Usb => EmissionModulatorKind::SsbUpper,
        Modulation::Lsb => EmissionModulatorKind::SsbLower,
        Modulation::Noise => EmissionModulatorKind::Noise,
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_register_emission(
    handle_ptr: *mut c_void,
    desc: *const HdEmissionDesc,
    out_emission_id: *mut u32,
) -> i32 {
    safe_call(|| {
        if desc.is_null() || out_emission_id.is_null() {
            return HD_ERR_NULL;
        }
        // SAFETY: caller responsibility: desc points at a fully
        // initialised HdEmissionDesc kept alive for the duration
        // of this call.
        let d = unsafe { &*desc };
        // SAFETY: handle is interpreted as a boxed SpectrumManager.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let modulation = match parse_modulation(d.modulation) {
            Some(m) => m,
            None => return HD_ERR_INVALID_ARG,
        };
        if !(d.tx_power_w.is_finite() && d.tx_power_w >= 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        if !(d.carrier_hz.is_finite() && d.carrier_hz > 0.0) {
            return HD_ERR_INVALID_ARG;
        }
        //fuck

        let descriptor = Emission {
            id: EmissionId(0),
            modulation,
            pos_x: d.pos_x,
            pos_y: d.pos_y,
            pos_z: d.pos_z,
            tx_power_w: d.tx_power_w,
            antenna_gain: d.antenna_gain.max(1e-6),
            carrier_hz: d.carrier_hz,
            bandwidth_hz: d.bandwidth_hz.max(1.0),
        };
        let velocity = [d.vel_x, d.vel_y, d.vel_z];

        let emission = match d.jam_profile {
            0 => {
                // Audio emission. pcm_capacity guards against the
                // caller forgetting to set it; we clamp to a sane
                // minimum to keep the ring usable.
                let cap = d.pcm_capacity.max(2048) as usize;
                let kind = match modulator_kind_for(d.modulation) {
                    Some(k) => k,
                    None => return HD_ERR_INVALID_ARG,
                };
                ActiveEmission::audio(descriptor, velocity, kind, cap, ENGINE_SAMPLE_RATE_HZ)
            }
            1 | 2 => {
                if !(d.jam_sigma.is_finite() && d.jam_sigma >= 0.0) {
                    return HD_ERR_INVALID_ARG;
                }
                let profile = if d.jam_profile == 1 {
                    JamProfile::Continuous
                } else {
                    if !(d.jam_rate_hz.is_finite() && d.jam_rate_hz > 0.0) {
                        return HD_ERR_INVALID_ARG;
                    }
                    JamProfile::Pulsed { rate_hz: d.jam_rate_hz }
                };
                // Seed derived from descriptor fields so the test
                // path is deterministic without an explicit knob.
                let seed = d.carrier_hz.to_bits() ^ ((d.pos_x.to_bits() as u64) << 16);
                let jammer = NoiseJammer::new(seed, d.jam_sigma, profile, ENGINE_SAMPLE_RATE_HZ);
                ActiveEmission::jammer(descriptor, velocity, jammer)
            }
            _ => return HD_ERR_INVALID_ARG,
        };

        let id = manager.register_emission(emission);
        // SAFETY: out_emission_id non-null by the early return.
        unsafe { *out_emission_id = id.0 };
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_unregister_emission(handle_ptr: *mut c_void, emission_id: u32) -> i32 {
    safe_call(|| {
        // SAFETY: see register_emission.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        if manager.unregister_emission(EmissionId(emission_id)).is_some() {
            HD_OK
        } else {
            HD_ERR_NOT_FOUND
        }
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_push_audio(
    handle_ptr: *mut c_void,
    emission_id: u32,
    samples: *const f32,
    sample_count: u64,
) -> i32 {
    safe_call(|| {
        if samples.is_null() && sample_count > 0 {
            return HD_ERR_NULL;
        }
        // SAFETY: see register_emission.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let em = match manager.emission_mut(EmissionId(emission_id)) {
            Some(e) => e,
            None => return HD_ERR_NOT_FOUND,
        };
        if sample_count == 0 {
            return HD_OK;
        }
        let n = sample_count as usize;
        // SAFETY: caller guarantees samples points at n initialised
        // f32 values. The slice is only borrowed for the duration
        // of this call.
        let slice = unsafe { std::slice::from_raw_parts(samples, n) };
        em.push_audio(slice);
        HD_OK
    })
}

#[no_mangle]
pub extern "C" fn hd_manager_set_emission_position(
    handle_ptr: *mut c_void,
    emission_id: u32,
    x: f32,
    y: f32,
    z: f32,
    vx: f32,
    vy: f32,
    vz: f32,
) -> i32 {
    safe_call(|| {
        // SAFETY: see register_emission.
        let manager = match unsafe { handle::as_mut::<SpectrumManager>(handle_ptr) } {
            Some(m) => m,
            None => return HD_ERR_NULL,
        };
        let em = match manager.emission_mut(EmissionId(emission_id)) {
            Some(e) => e,
            None => return HD_ERR_NOT_FOUND,
        };
        em.descriptor.pos_x = x;
        em.descriptor.pos_y = y;
        em.descriptor.pos_z = z;
        em.velocity = [vx, vy, vz];
        HD_OK
    })
}