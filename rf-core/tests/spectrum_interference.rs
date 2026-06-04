//! Two transmitters in band: the receiver should see the sum.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialTable, SolarActivity, SolverConfig,
};
use rf_core::spectrum::{
    ActiveEmission, EmissionModulatorKind, ReceiverConfig, SpectrumManager,
    SpectrumManagerConfig, ENGINE_SAMPLE_RATE_HZ,
};
use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::modulation::Modulation;

#[test]
fn two_transmitters_sum_in_band() {
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-50, -10, -50], [120, 30, 120]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let mk = |x: f32, carrier: f64| {
        let mut em = ActiveEmission::audio(
            Emission {
                id: EmissionId(0),
                modulation: Modulation::NFm,
                pos_x: x,
                pos_y: 5.0,
                pos_z: 0.0,
                tx_power_w: 1.0,
                antenna_gain: 1.0,
                carrier_hz: carrier,
                bandwidth_hz: 15_000.0,
            },
            [0.0; 3],
            EmissionModulatorKind::NarrowFm,
            4096,
            ENGINE_SAMPLE_RATE_HZ,
        );
        em.push_audio(&vec![0.5; 4096]);
        em
    };

    let id_a = mgr.register_emission(mk(10.0, 145_000_000.0));
    let id_b = mgr.register_emission(mk(-10.0, 145_005_000.0));
    assert_ne!(id_a, id_b);

    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 145_002_500.0,
        bandwidth_hz: 15_000.0,
        position: [0.0, 5.0, 20.0],
        ..ReceiverConfig::default()
    });

    let chunk = mgr
        .make_and_mix(
            rx_id,
            &grid,
            &materials,
            &iono,
            SolverConfig::default(),
            1024,
            0,
            12.0,
        )
        .expect("mix");

    // Expect a non zero envelope after AGC settles. Skip the first
    // 100 samples to let the AGC ride up.
    let skip = 100;
    let mean_mag: f32 = chunk.samples[skip..]
        .iter()
        .map(|s| s.magnitude())
        .sum::<f32>()
        / (chunk.samples.len() - skip) as f32;
    assert!(mean_mag > 0.01, "mean magnitude {mean_mag} suspiciously low");
}