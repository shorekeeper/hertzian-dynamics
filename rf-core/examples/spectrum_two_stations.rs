//! Two transmitters on adjacent FM channels. Plot the FFT of the
//! receiver chunk to see the carriers in band.
//!
//! Run: cargo run --example spectrum_two_stations
//!
//! What to look for:
//!   * Two peaks in the dumped spectrum.csv, one at the frequency
//!     offset of station A from the receiver (negative side) and
//!     one at the offset of B (positive side).
//!   * Their relative magnitudes track the relative tx powers.
//!   * The noise floor sits well below both carriers.

use rf_core::dsp::fft::{fft, FftDirection};
use rf_core::io::{csv, example_output_dir};
use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialTable, SolarActivity, SolverConfig,
};
use rf_core::spectrum::{
    ActiveEmission, EmissionModulatorKind, ReceiverConfig, SpectrumManager,
    SpectrumManagerConfig, ENGINE_SAMPLE_RATE_HZ,
};
use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::modulation::Modulation;

fn main() {
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-100, -10, -100], [240, 30, 240]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let fs = ENGINE_SAMPLE_RATE_HZ;
    let pcm_a: Vec<f32> = (0..8192)
        .map(|i| (2.0 * std::f32::consts::PI * 1000.0 * i as f32 / fs).sin() * 0.7)
        .collect();
    let pcm_b: Vec<f32> = (0..8192)
        .map(|i| (2.0 * std::f32::consts::PI * 1500.0 * i as f32 / fs).sin() * 0.5)
        .collect();

    let make_tx = |x: f32, carrier: f64, power: f32| {
        ActiveEmission::audio(
            Emission {
                id: EmissionId(0),
                modulation: Modulation::NFm,
                pos_x: x,
                pos_y: 5.0,
                pos_z: 0.0,
                tx_power_w: power,
                antenna_gain: 1.0,
                carrier_hz: carrier,
                bandwidth_hz: 15_000.0,
            },
            [0.0; 3],
            EmissionModulatorKind::NarrowFm,
            16_384,
            fs,
        )
    };

    let id_a = mgr.register_emission(make_tx(-20.0, 145_000_000.0, 5.0));
    let id_b = mgr.register_emission(make_tx(20.0, 145_010_000.0, 2.0));
    mgr.emission_mut(id_a).unwrap().push_audio(&pcm_a);
    mgr.emission_mut(id_b).unwrap().push_audio(&pcm_b);

    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 145_005_000.0,
        bandwidth_hz: 30_000.0,
        modulation: Modulation::NFm,
        antenna_gain: 1.0,
        position: [0.0, 5.0, 30.0],
        velocity: [0.0; 3],
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

    let mut spec: Vec<_> = chunk.samples.iter().cloned().collect();
    fft(&mut spec, FftDirection::Forward).unwrap();

    let dir = example_output_dir("spectrum_two_stations");
    let rows: Vec<(f32, f32)> = (0..n)
        .map(|k| {
            let f = if k < n / 2 {
                k as f32 * fs / n as f32
            } else {
                (k as f32 - n as f32) * fs / n as f32
            };
            let mag_db = 20.0 * spec[k].magnitude().max(1e-12).log10();
            (f, mag_db)
        })
        .collect();
    csv::write_pair_csv(dir.join("spectrum.csv"), "offset_hz", "mag_db", &rows).unwrap();

    println!("noise floor (header): {} W", chunk.header.noise_floor_w);
    println!("samples: {}", chunk.samples.len());
    println!("output: {}", dir.display());
}