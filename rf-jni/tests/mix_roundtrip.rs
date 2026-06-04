//! End-to-end: register an FM voice emission, push a tone, mix one
//! chunk, verify samples are populated.

use std::os::raw::c_void;
use std::ptr;

use rf_jni::emission::HdEmissionDesc;
use rf_jni::mix::HdChunkHeader;
use rf_jni::receiver::HdReceiverConfig;
use rf_jni::*;
use rf_core::types::iq::Iq;

const HD_MOD_NFM: u32 = 2;
const FS: f32 = 48_000.0;

#[test]
fn register_emit_mix_demodulate_shape() {
    // Bring the whole stack up.
    let mut core: *mut c_void = ptr::null_mut();
    assert_eq!(hd_core_create(&mut core), HD_OK);
    let mut mgr: *mut c_void = ptr::null_mut();
    assert_eq!(hd_manager_create(core, &mut mgr), HD_OK);
    let mut grid: *mut c_void = ptr::null_mut();
    assert_eq!(hd_grid_create(1.0, &mut grid), HD_OK);
    let mut materials: *mut c_void = ptr::null_mut();
    assert_eq!(hd_materials_create_defaults(&mut materials), HD_OK);
    let mut iono: *mut c_void = ptr::null_mut();
    assert_eq!(hd_iono_create(1, &mut iono), HD_OK);

    // Emission descriptor.
    let desc = HdEmissionDesc {
        modulation: HD_MOD_NFM,
        pos_x: 0.0,
        pos_y: 5.0,
        pos_z: 0.0,
        vel_x: 0.0,
        vel_y: 0.0,
        vel_z: 0.0,
        tx_power_w: 5.0,
        antenna_gain: 1.0,
        carrier_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
        pcm_capacity: 16_384,
        jam_profile: 0,
        jam_rate_hz: 0.0,
        jam_sigma: 0.0,
    };
    let mut em_id: u32 = 0;
    assert_eq!(
        hd_manager_register_emission(mgr, &desc as *const HdEmissionDesc, &mut em_id),
        HD_OK
    );
    assert!(em_id > 0);

    // Push a 1 kHz tone.
    let n = 4096usize;
    let tone: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * 1_000.0 * i as f32 / FS).sin() * 0.7)
        .collect();
    let rc = hd_manager_push_audio(mgr, em_id, tone.as_ptr(), n as u64);
    assert_eq!(rc, HD_OK);

   let rx_cfg = HdReceiverConfig {
        tuned_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
        modulation: HD_MOD_NFM,
        antenna_gain: 1.0,
        pos_x: 50.0,
        pos_y: 5.0,
        pos_z: 0.0,
        vel_x: 0.0,
        vel_y: 0.0,
        vel_z: 0.0,
        agc_target: 0.0,
        agc_attack_seconds: 0.0,
        agc_release_seconds: 0.0,
        agc_max_gain: 0.0,
        agc_min_gain: 0.0,
        noise_figure_db: 12.0,
        noise_environment: 2,
    };
    let mut rx_id: u32 = 0;
    assert_eq!(
        hd_manager_register_receiver(mgr, &rx_cfg as *const HdReceiverConfig, &mut rx_id),
        HD_OK
    );

    // Mix one chunk.
    let mut header = HdChunkHeader {
        center_hz: 0.0,
        sample_rate_hz: 0.0,
        bandwidth_hz: 0.0,
        sample_count: 0,
        sequence: 0,
        server_tick: 0,
        noise_floor_w: 0.0,
        signal_power_w: 0.0,
    };
    let mut samples = vec![Iq::ZERO; n];
    let rc = hd_manager_mix_chunk(
        mgr,
        grid,
        materials,
        iono,
        rx_id,
        n as u32,
        0,
        12.0,
        &mut header,
        samples.as_mut_ptr(),
    );
    assert_eq!(rc, HD_OK);
    assert_eq!(header.sample_count, n as u32);
    assert!((header.sample_rate_hz - FS).abs() < 1.0);

    // After AGC the average magnitude should be near the target.
    // Explicit deref on `s` is needed because the closure parameter
    // is `&Iq` and `magnitude` is defined on `Iq` (by value).
    let avg = samples.iter().map(|s| (*s).magnitude()).sum::<f32>() / n as f32;
    assert!(avg > 0.05, "average magnitude {avg} too low");

    // Tear down.
    assert_eq!(hd_iono_destroy(iono), HD_OK);
    assert_eq!(hd_materials_destroy(materials), HD_OK);
    assert_eq!(hd_grid_destroy(grid), HD_OK);
    assert_eq!(hd_manager_destroy(mgr), HD_OK);
    assert_eq!(hd_core_destroy(core), HD_OK);
}