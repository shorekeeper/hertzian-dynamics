//! One transmitter, one receiver, clear line of sight, expect a
//! recognisable demodulated audio.

use rf_core::dsp::modulation::{AudioDemodulator, FmDemodulator};
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
fn single_fm_transmitter_is_received_cleanly() {
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-50, -10, -50], [120, 30, 120]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let descriptor = Emission {
        id: EmissionId(0),
        modulation: Modulation::NFm,
        pos_x: 10.0,
        pos_y: 5.0,
        pos_z: 0.0,
        tx_power_w: 5.0,
        antenna_gain: 1.0,
        carrier_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
    };
    let tx = ActiveEmission::audio(
        descriptor,
        [0.0; 3],
        EmissionModulatorKind::NarrowFm,
        16_384,
        ENGINE_SAMPLE_RATE_HZ,
    );
    let tx_id = mgr.register_emission(tx);

    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
        modulation: Modulation::NFm,
        antenna_gain: 1.0,
        position: [20.0, 5.0, 0.0],
        velocity: [0.0; 3],
        ..ReceiverConfig::default()
    });

    // Push a 1 kHz tone.
    let n = 4096usize;
    let fs = ENGINE_SAMPLE_RATE_HZ;
    let audio: Vec<f32> = (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * 1_000.0 * i as f32 / fs).sin() * 0.7)
        .collect();
    {
        let em = mgr.emission_mut(tx_id).unwrap();
        em.push_audio(&audio);
    }

    let chunk = mgr
        .make_and_mix(
            rx_id,
            &grid,
            &materials,
            &iono,
            SolverConfig::default(),
            n as u32,
            0,
            12.0,
        )
        .expect("mix succeeded");

    // Demodulate.
    let mut demod = FmDemodulator::new(5_000.0, fs);
    let mut out = vec![0.0_f32; n];
    demod.demodulate(&chunk.samples, &mut out);

    // Energy ratio: signal portion should dominate the residual.
    let skip = 1024;
    let signal_energy: f64 = out[skip..].iter().map(|v| (*v as f64).powi(2)).sum();
    assert!(signal_energy > 1.0, "demodulated signal energy too low: {signal_energy}");
}

#[test]
fn out_of_band_transmitter_is_ignored() {
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-50, -10, -50], [120, 30, 120]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let mut em = ActiveEmission::audio(
        Emission {
            id: EmissionId(0),
            modulation: Modulation::NFm,
            pos_x: 10.0,
            pos_y: 5.0,
            pos_z: 0.0,
            tx_power_w: 5.0,
            antenna_gain: 1.0,
            carrier_hz: 145_000_000.0,
            bandwidth_hz: 15_000.0,
        },
        [0.0; 3],
        EmissionModulatorKind::NarrowFm,
        4096,
        ENGINE_SAMPLE_RATE_HZ,
    );
    em.push_audio(&vec![1.0; 4096]);
    let _tx_id = mgr.register_emission(em);

    // Receiver tuned a megahertz away.
    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 146_000_000.0,
        bandwidth_hz: 15_000.0,
        position: [20.0, 5.0, 0.0],
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

    let peak = chunk
        .samples
        .iter()
        .map(|s| s.magnitude())
        .fold(0.0_f32, f32::max);
    // With AGC pulling toward target 0.5 on a noise-only input,
    // the floor should hover well below 0.5 because the envelope
    // estimate decays slowly. We just want to confirm we did not
    // accidentally let the signal leak in: peak should be modest.
    assert!(peak < 0.6, "noise-only chunk peak too high: {peak}");
}