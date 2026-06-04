//! Type level unit tests. No Vulkan, no GPU required. Always run.

use rf_core::types::emission::{Emission, EmissionId};
use rf_core::types::spectrum::{SpectrumChunk, SpectrumChunkHeader};
use rf_core::{Iq, Modulation};

#[test]
fn iq_arithmetic_matches_textbook_identities() {
    let a = Iq::new(1.0, 2.0);
    let b = Iq::new(3.0, 4.0);

    let sum = a.add(b);
    assert_eq!(sum, Iq::new(4.0, 6.0));

    // (1 + 2j)(3 + 4j) = (3 - 8) + (4 + 6)j = -5 + 10j.
    let prod = a.mul(b);
    assert!((prod.i - (-5.0)).abs() < 1e-6);
    assert!((prod.q - 10.0).abs() < 1e-6);

    // |1 + 2j|^2 = 5.
    assert!((a.power() - 5.0).abs() < 1e-6);
    assert!((a.magnitude() - 5.0f32.sqrt()).abs() < 1e-6);

    // conj(a) * a yields real magnitude squared.
    let pr = a.conj().mul(a);
    assert!(pr.q.abs() < 1e-6);
    assert!((pr.i - 5.0).abs() < 1e-6);
}

#[test]
fn iq_layout_is_two_packed_f32() {
    assert_eq!(std::mem::size_of::<Iq>(), 8);
    assert_eq!(std::mem::align_of::<Iq>(), 4);
}

#[test]
fn modulation_discriminants_roundtrip() {
    for m in [
        Modulation::Cw,
        Modulation::Am,
        Modulation::NFm,
        Modulation::WFm,
        Modulation::Usb,
        Modulation::Lsb,
        Modulation::Noise,
    ] {
        let raw = m.as_u32();
        let back = Modulation::from_u32(raw).expect("known variant");
        assert_eq!(back, m);
    }
    assert!(Modulation::from_u32(0xFFFF_FFFF).is_none());
}

#[test]
fn emission_constructs_with_reasonable_fields() {
    let e = Emission {
        id: EmissionId(7),
        modulation: Modulation::Am,
        pos_x: 100.0,
        pos_y: 64.0,
        pos_z: -50.0,
        tx_power_w: 100.0,
        antenna_gain: 2.0,
        carrier_hz: 7_055_000.0,
        bandwidth_hz: 6_000.0,
    };
    assert_eq!(e.id, EmissionId(7));
    assert!((e.carrier_hz - 7_055_000.0).abs() < 1e-3);
}

#[test]
fn spectrum_chunk_allocates_exact_sample_count() {
    let header = SpectrumChunkHeader {
        center_hz: 100_000_000.0,
        sample_rate_hz: 48_000.0,
        bandwidth_hz: 15_000.0,
        sample_count: 1024,
        sequence: 1,
        server_tick: 42,
        noise_floor_w: 1e-15,
        signal_power_w: 0.0,
    };
    let chunk = SpectrumChunk::allocate(header);
    assert_eq!(chunk.samples.len(), 1024);
    assert!(chunk.samples.iter().all(|s| *s == Iq::ZERO));
    assert_eq!(chunk.header.sample_count, 1024);
}