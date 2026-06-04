//! Voice transmitter at modest power; jammer next to the receiver
//! at higher power. The chunk shows the voice envelope is buried in
//! noise.
//!
//! Run: cargo run --example spectrum_jammer_vs_voice
//!
//! What to look for:
//!   * `voice_avg_db_above_floor`: should be substantially positive
//!     when only the voice transmitter is on, and approach zero
//!     when the jammer is on.

use rf_core::dsp::modulation::{JamProfile, NoiseJammer};
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
    let fs = ENGINE_SAMPLE_RATE_HZ;
    let mut mgr = SpectrumManager::new(SpectrumManagerConfig::default());
    let grid = DenseVoxelGrid::new(1.0, [-100, -10, -100], [240, 30, 240]);
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    let voice = ActiveEmission::audio(
        Emission {
            id: EmissionId(0),
            modulation: Modulation::NFm,
            pos_x: 0.0,
            pos_y: 5.0,
            pos_z: 0.0,
            tx_power_w: 5.0,
            antenna_gain: 1.0,
            carrier_hz: 145_000_000.0,
            bandwidth_hz: 15_000.0,
        },
        [0.0; 3],
        EmissionModulatorKind::NarrowFm,
        16_384,
        fs,
    );
    let voice_id = mgr.register_emission(voice);
    let tone: Vec<f32> = (0..8192)
        .map(|i| (2.0 * std::f32::consts::PI * 800.0 * i as f32 / fs).sin() * 0.7)
        .collect();
    mgr.emission_mut(voice_id).unwrap().push_audio(&tone);

    let rx_id = mgr.register_receiver(ReceiverConfig {
        tuned_hz: 145_000_000.0,
        bandwidth_hz: 15_000.0,
        modulation: Modulation::NFm,
        antenna_gain: 1.0,
        position: [50.0, 5.0, 0.0],
        velocity: [0.0; 3],
        ..ReceiverConfig::default()
    });

    // Voice only.
    let chunk_clean = mgr
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
        .expect("mix");
    let clean_avg = avg_mag(&chunk_clean);

    // Add a jammer right next to the receiver.
    let jammer = NoiseJammer::new(0xBEEF, 1.0, JamProfile::Continuous, fs);
    let jammer_em = ActiveEmission::jammer(
        Emission {
            id: EmissionId(0),
            modulation: Modulation::Noise,
            pos_x: 50.0,
            pos_y: 5.0,
            pos_z: 5.0,
            tx_power_w: 200.0,
            antenna_gain: 1.0,
            carrier_hz: 145_000_000.0,
            bandwidth_hz: 30_000.0,
        },
        [0.0; 3],
        jammer,
    );
    let _jam_id = mgr.register_emission(jammer_em);

    let chunk_jammed = mgr
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
        .expect("mix");
    let jammed_avg = avg_mag(&chunk_jammed);

    println!("voice only avg |y|: {clean_avg:.4}");
    println!("jammed avg    |y|: {jammed_avg:.4}");
    println!("noise floor (jammed header): {:.3e} W", chunk_jammed.header.noise_floor_w);

    // The AGC normalises both, but the jammed chunk loses the tone
    // structure: a simple check is the variance of the magnitude,
    // which should drop sharply when the jammer is on (signal flat
    // noise) compared to a modulated voice carrier (envelope swing).
    let var_clean = mag_variance(&chunk_clean);
    let var_jammed = mag_variance(&chunk_jammed);
    println!("|y| variance clean : {var_clean:.4}");
    println!("|y| variance jammed: {var_jammed:.4}");
}

fn avg_mag(c: &rf_core::SpectrumChunk) -> f32 {
    c.samples.iter().map(|s| s.magnitude()).sum::<f32>() / c.samples.len() as f32
}

fn mag_variance(c: &rf_core::SpectrumChunk) -> f32 {
    let mean = avg_mag(c);
    c.samples
        .iter()
        .map(|s| (s.magnitude() - mean).powi(2))
        .sum::<f32>()
        / c.samples.len() as f32
}