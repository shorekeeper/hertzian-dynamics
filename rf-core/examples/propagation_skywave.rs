//! Compare ground wave and skywave at HF over a 1500 km path,
//! day vs night, low vs high solar activity.
//!
//! Run: cargo run --example propagation_skywave
//!
//! What to look for:
//!   * At 1500 km the ground wave is unusable (FSPL alone exceeds
//!     130 dB at 10 MHz).
//!   * Skywave succeeds at night for the medium activity LUT at
//!     7 MHz; daytime at 7 MHz pays heavy D layer absorption.
//!   * At 25 MHz the path is closed at night because we sit above
//!     the night MUF.

use rf_core::propagation::{IonosphereLut, SolarActivity};

fn main() {
    let dist_m = 1_500_000.0_f32;

    for activity in [SolarActivity::Low, SolarActivity::Medium, SolarActivity::High] {
        let lut = IonosphereLut::for_activity(activity);
        for freq_mhz in [3.5_f64, 7.0, 14.0, 21.0, 28.0] {
            let f = freq_mhz * 1.0e6;
            let day = lut.evaluate(f, dist_m, 12.0);
            let night = lut.evaluate(f, dist_m, 3.0);
            println!(
                "{activity:?} {freq_mhz:>5.1} MHz | day MUF {:>5.1} MHz abs {:>6.1} dB hops {} | night MUF {:>5.1} MHz abs {:>6.1} dB hops {}",
                day.muf_hz / 1.0e6,
                day.absorption_db,
                day.hops,
                night.muf_hz / 1.0e6,
                night.absorption_db,
                night.hops
            );
        }
        println!();
    }
}