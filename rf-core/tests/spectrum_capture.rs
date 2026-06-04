//! Strong transmitter on the same carrier dominates the weak one.
//! With AGC and no demodulator, the test inspects the chunk power
//! contribution from each source by running two mixes: one with
//! both transmitters, one with only the weak one. The strong-only
//! case should be visibly closer to the both-on case than to the
//! weak-only case.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialTable, SolarActivity, SolverConfig,
};
use rf_core::spectrum::{
    ActiveEmission, EmissionModulatorKind, ReceiverConfig, SpectrumManager,
    SpectrumManagerConfig, ENGINE_SAMPLE_RATE_HZ,
};
use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::modulation::Modulation;

fn build_manager() -> SpectrumManager {
    SpectrumManager::new(SpectrumManagerConfig::default())
}

fn pcm_tone(n: usize, hz: f32, amp: f32) -> Vec<f32> {
    (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * hz * i as f32 / ENGINE_SAMPLE_RATE_HZ).sin() * amp)
        .collect()
}

#[test]
fn strong_carrier_dominates() {
    let mut mgr = build_manager();
    let grid = DenseVoxelGrid::new(1.0, [-100, -10, -100], [240, 30, 240]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let make_tx = |power_w: f32, audio_hz: f32| {
        let mut em = ActiveEmission::audio(
            Emission {
                id: EmissionId(0),
                modulation: Modulation::NFm,
                pos_x: 10.0,
                pos_y: 5.0,
                pos_z: 0.0,
                tx_power_w: power_w,
                antenna_gain: 1.0,
                carrier_hz: 145_000_000.0,
                bandwidth_hz: 15_000.0,
            },
            [0.0; 3],
            EmissionModulatorKind::NarrowFm,
            4096,
            ENGINE_SAMPLE_RATE_HZ,
        );
        em.push_audio(&pcm_tone(4096, audio_hz, 0.7));
        em
    };

    let weak_id = mgr.register_emission(make_tx(1.0, 1000.0));
    let strong_id = mgr.register_emission(make_tx(100.0, 2500.0));

    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
        position: [50.0, 5.0, 0.0],
        ..ReceiverConfig::default()
    });

    // Both on. Inspect the average magnitude.
    let chunk_both = mgr
        .make_and_mix(
            rx_id,
            &grid,
            &materials,
            &iono,
            SolverConfig::default(),
            2048,
            0,
            12.0,
        )
        .expect("mix both");

    // Strong only.
    mgr.unregister_emission(weak_id).expect("drop weak");
    // Reset receiver AGC by clearing offsets and AGC; easiest is to
    // re-register receivers between sub tests in real use, but here
    // we keep the same one and rely on the AGC settling between
    // calls. The relative comparison still holds.
    let chunk_strong = mgr
        .make_and_mix(
            rx_id,
            &grid,
            &materials,
            &iono,
            SolverConfig::default(),
            2048,
            1,
            12.0,
        )
        .expect("mix strong");

    // Restore weak only.
    let _strong_dropped = mgr.unregister_emission(strong_id);
    // Push audio for the weak transmitter again? The dropped weak
    // emission cannot be reanimated; instead register a fresh one.
    let weak2 = make_tx(1.0, 1000.0);
    let _ = mgr.register_emission(weak2);
    let chunk_weak = mgr
        .make_and_mix(
            rx_id,
            &grid,
            &materials,
            &iono,
            SolverConfig::default(),
            2048,
            2,
            12.0,
        )
        .expect("mix weak");

    let avg_mag = |c: &rf_core::SpectrumChunk| -> f32 {
        c.samples.iter().map(|s| s.magnitude()).sum::<f32>() / c.samples.len() as f32
    };
    let mag_both = avg_mag(&chunk_both);
    let mag_strong = avg_mag(&chunk_strong);
    let mag_weak = avg_mag(&chunk_weak);

    // After AGC the absolute levels are similar, so we compare the
    // ratio of differences. The both-on case should be much closer
    // to the strong-only case than to the weak-only case.
    let diff_strong = (mag_both - mag_strong).abs();
    let diff_weak = (mag_both - mag_weak).abs();
    assert!(
        diff_strong < diff_weak,
        "expected diff_strong({diff_strong}) < diff_weak({diff_weak}); mags both={mag_both} strong={mag_strong} weak={mag_weak}"
    );
}