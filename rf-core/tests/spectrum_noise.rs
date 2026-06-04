//! System noise floor model checks.

use rf_core::spectrum::noise_floor::{
    external_noise_factor, man_made_noise_factor_db, system_noise_factor, NoiseEnvironment,
};

#[test]
fn man_made_noise_falls_with_frequency() {
    // HF is far noisier than VHF in the same environment.
    let hf = man_made_noise_factor_db(7.0e6, NoiseEnvironment::Residential);
    let vhf = man_made_noise_factor_db(145.0e6, NoiseEnvironment::Residential);
    assert!(hf > vhf + 20.0, "HF {hf} should sit well above VHF {vhf}");
}

#[test]
fn environments_order_by_quietness() {
    let f = 14.0e6;
    let quiet = external_noise_factor(f, NoiseEnvironment::QuietRural);
    let rural = external_noise_factor(f, NoiseEnvironment::Rural);
    let res = external_noise_factor(f, NoiseEnvironment::Residential);
    let city = external_noise_factor(f, NoiseEnvironment::City);
    assert!(quiet < rural, "{quiet} !< {rural}");
    assert!(rural < res, "{rural} !< {res}");
    assert!(res < city, "{res} !< {city}");
}

#[test]
fn higher_noise_figure_raises_floor() {
    // At VHF, where external noise is modest, a worse receiver figure
    // clearly raises the system noise factor.
    let good = system_noise_factor(6.0, 145.0e6, NoiseEnvironment::Residential);
    let bad = system_noise_factor(20.0, 145.0e6, NoiseEnvironment::Residential);
    assert!(bad > good, "bad NF {bad} should exceed good NF {good}");
}

#[test]
fn factor_never_below_unity() {
    // Even a perfect receiver under the quietest sky stays at or above
    // the thermal reference.
    let f = system_noise_factor(0.0, 250.0e6, NoiseEnvironment::QuietRural);
    assert!(f >= 1.0, "factor {f} dropped below unity");
}

#[test]
fn hf_is_external_noise_limited() {
    // On HF the external noise dwarfs even a poor receiver figure, so
    // the receiver noise figure barely moves the floor.
    let nf_low = system_noise_factor(3.0, 7.0e6, NoiseEnvironment::City);
    let nf_high = system_noise_factor(20.0, 7.0e6, NoiseEnvironment::City);
    let ratio = nf_high / nf_low;
    assert!(ratio < 1.05, "HF floor moved too much with NF: ratio {ratio}");
}