//! Free space loss vs distance at three frequencies. Outputs CSV
//! suitable for plotting.
//!
//! Run: cargo run --example propagation_distance
//!
//! What to look for:
//!   * Every doubling of distance adds about 6 dB to the loss.
//!   * At 100 m the loss should sit near 52 dB at 100 MHz.
//!   * The 10 MHz curve is 20 dB below the 100 MHz curve at the
//!     same distance because lambda is ten times larger.

use rf_core::io::{csv, example_output_dir};
use rf_core::propagation::free_space_loss_db;

fn main() {
    let dir = example_output_dir("propagation_distance");
    let freqs = [1.0e6, 10.0e6, 100.0e6];
    let mut rows: Vec<(f32, f32)> = Vec::new();
    for f in freqs.iter() {
        let mut d = 1.0_f32;
        while d <= 1.0e5 {
            let l = free_space_loss_db(d, *f);
            rows.push((d, l));
            d *= 1.2;
        }
    }
    csv::write_pair_csv(dir.join("loss.csv"), "distance_m", "loss_db", &rows).unwrap();
    println!("rows: {}", rows.len());
    println!("output: {}", dir.display());
    println!("100 MHz at 100 m: {:.1} dB", free_space_loss_db(100.0, 100.0e6));
    println!("100 MHz at 1 km : {:.1} dB", free_space_loss_db(1000.0, 100.0e6));
    println!("100 MHz at 10 km: {:.1} dB", free_space_loss_db(10_000.0, 100.0e6));
}