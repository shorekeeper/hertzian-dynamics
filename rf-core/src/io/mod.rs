//! Tiny dependency free I/O helpers for examples and diagnostics.
//!
//! Not intended for production audio playback (no resampling, no
//! float WAV, no metadata). The goal is: dump a signal, open it in
//! Audacity or plot it in Python.

pub mod wav;
pub mod csv;

use std::path::PathBuf;

pub fn example_output_dir(name: &str) -> PathBuf {
    let target_dir = std::env::var_os("CARGO_TARGET_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target"));
    let p = target_dir.join("hertzian_out").join(name);
    std::fs::create_dir_all(&p).expect("create example output dir");
    p
}