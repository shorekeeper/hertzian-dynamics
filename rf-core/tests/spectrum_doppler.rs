//! Receiver moving toward the transmitter sees a positive Doppler
//! offset; the mixer translates that into a phase rotation across
//! the chunk that increases the dominant tone frequency.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialTable, SolarActivity, SolverConfig,
};
use rf_core::spectrum::{
    ActiveEmission, EmissionModulatorKind, ReceiverConfig, SpectrumManager,
    SpectrumManagerConfig, ENGINE_SAMPLE_RATE_HZ,
};
use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::modulation::Modulation;
use rf_core::dsp::fft::{fft, FftDirection};
use rf_core::types::iq::Iq;

#[test]
fn doppler_shifts_chunk_spectrum() {
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-50, -10, -50], [240, 30, 240]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let descriptor = Emission {
        id: EmissionId(0),
        modulation: Modulation::Cw,
        pos_x: 0.0,
        pos_y: 5.0,
        pos_z: 0.0,
        tx_power_w: 1.0,
        antenna_gain: 1.0,
        carrier_hz: 100_000_000.0,
        bandwidth_hz: 200.0,
    };
    let mut em = ActiveEmission::audio(
        descriptor,
        [0.0; 3],
        EmissionModulatorKind::Cw,
        8192,
        ENGINE_SAMPLE_RATE_HZ,
    );
    em.push_audio(&vec![1.0; 8192]);
    let _tx_id = mgr.register_emission(em);

    // Receiver running at extreme velocity. 1e6 m/s is comically
    // unphysical but pulls the Doppler well clear of the FFT bin
    // size at 48 kHz so the test does not depend on subtle
    // resolution.
    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 100_000_000.0,
        bandwidth_hz: 4_000.0,
        modulation: Modulation::Cw,
        antenna_gain: 1.0,
        position: [100.0, 5.0, 0.0],
        velocity: [-1.0e6, 0.0, 0.0],
        ..ReceiverConfig::default()
    });

    let n = 4096usize;
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
        .expect("mix");

    // Find the dominant FFT bin. With a closing velocity of 1e6
    // m/s on a 100 MHz carrier the Doppler is 333.5 kHz which
    // aliases inside the 48 kHz Nyquist; we only check it is not
    // sitting at zero.
    let mut spec: Vec<Iq> = chunk.samples.iter().cloned().collect();
    fft(&mut spec, FftDirection::Forward).unwrap();
    let mut best = 0usize;
    let mut best_mag = 0.0_f32;
    for (k, s) in spec.iter().enumerate() {
        let m = s.magnitude();
        if m > best_mag {
            best_mag = m;
            best = k;
        }
    }
    assert!(best != 0, "dominant FFT bin sits at DC despite a strong Doppler offset");
}