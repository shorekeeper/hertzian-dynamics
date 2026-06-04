//! Round trip tests for every modulator and demodulator pair.

use rf_core::dsp::modulation::{
    AmDemodulator, AmModulator, AudioDemodulator, AudioModulator, CwDemodulator, CwModulator,
    FmDemodulator, FmModulator, Sideband, SsbDemodulator, SsbModulator,
};
use rf_core::types::iq::Iq;

const FS: f32 = 48_000.0;

fn make_tone(hz: f32, n: usize, amp: f32) -> Vec<f32> {
    (0..n)
        .map(|i| (2.0 * std::f32::consts::PI * hz * i as f32 / FS).sin() * amp)
        .collect()
}

fn snr_db(reference: &[f32], measured: &[f32]) -> f32 {
    let n = reference.len().min(measured.len());
    let mut sig = 0.0_f64;
    let mut err = 0.0_f64;
    for i in 0..n {
        let r = reference[i] as f64;
        let m = measured[i] as f64;
        sig += r * r;
        err += (m - r) * (m - r);
    }
    if err <= 0.0 {
        return 200.0;
    }
    10.0 * (sig / err).log10() as f32
}

#[test]
fn am_round_trip_is_clean() {
    let n = 8192;
    let audio = make_tone(1_000.0, n, 0.5);
    let mut bb = vec![Iq::ZERO; n];
    let mut out = vec![0.0_f32; n];
    let mut m = AmModulator::new(0.8);
    let mut d = AmDemodulator::new(0.8);
    m.modulate(&audio, &mut bb);
    d.demodulate(&bb, &mut out);
    let skip = 2048;
    let snr = snr_db(&audio[skip..], &out[skip..]);
    assert!(snr > 30.0, "AM SNR too low: {snr}");
}

#[test]
fn fm_round_trip_is_clean() {
    let n = 8192;
    let audio = make_tone(1_000.0, n, 0.7);
    let mut bb = vec![Iq::ZERO; n];
    let mut out = vec![0.0_f32; n];
    let mut m = FmModulator::new(5_000.0, FS);
    let mut d = FmDemodulator::new(5_000.0, FS);
    m.modulate(&audio, &mut bb);
    d.demodulate(&bb, &mut out);
    let skip = 1024;
    let snr = snr_db(&audio[skip..], &out[skip..]);
    assert!(snr > 35.0, "FM SNR too low: {snr}");
}

#[test]
fn ssb_usb_round_trip_is_clean() {
    let n = 8192;
    let audio = make_tone(1_000.0, n, 0.5);
    let mut bb = vec![Iq::ZERO; n];
    let mut out = vec![0.0_f32; n];
    let mut m = SsbModulator::new(Sideband::Upper, 127);
    let mut d = SsbDemodulator::new(Sideband::Upper);
    m.modulate(&audio, &mut bb);
    d.demodulate(&bb, &mut out);
    let delay = m.group_delay_samples();
    let skip = 1024;
    let len = n - delay - skip;
    let snr = snr_db(&audio[skip..skip + len], &out[skip + delay..skip + delay + len]);
    assert!(snr > 25.0, "SSB SNR too low: {snr}");
}

#[test]
fn cw_key_state_is_recovered() {
    // Build a key pattern: 4 dots at 80 ms each, 48 kHz fs.
    let dot = (FS * 0.08) as usize;
    let n = dot * 8;
    let mut audio = vec![0.0_f32; n];
    for i in 0..4 {
        let start = i * dot * 2;
        for j in start..start + dot {
            audio[j] = 1.0;
        }
    }
    let mut bb = vec![Iq::ZERO; n];
    let mut m = CwModulator::new(0.005, FS);
    m.modulate(&audio, &mut bb);

    // Recover the on/off envelope by thresholding the magnitude
    // after the keyer ramp settles. The CwDemodulator mixes against
    // a sidetone for audio output; for the test we look at the
    // envelope directly.
    let mut recovered = vec![0.0_f32; n];
    for (b, r) in bb.iter().zip(recovered.iter_mut()) {
        *r = if b.magnitude() > 0.5 { 1.0 } else { 0.0 };
    }
    // Account for ramp duration: shrink each pulse by the ramp
    // length on each end before comparing.
    let ramp = (FS * 0.01) as usize;
    let mut errors = 0;
    for i in 0..4 {
        let start = i * dot * 2 + ramp;
        let end = start + dot - 2 * ramp;
        for j in start..end {
            if recovered[j] < 0.5 {
                errors += 1;
            }
        }
    }
    assert_eq!(errors, 0, "CW pulses lost samples: {errors}");
    // And confirm the sidetone demodulator at least runs without
    // panic and produces non zero output.
    let mut sidetone_out = vec![0.0_f32; n];
    let mut d = CwDemodulator::new(700.0, FS, 0.3);
    d.demodulate(&bb, &mut sidetone_out);
    let energy: f32 = sidetone_out.iter().map(|s| s * s).sum::<f32>();
    assert!(energy > 0.0);
}