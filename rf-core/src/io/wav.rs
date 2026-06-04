//! Minimal WAV writer, mono PCM 16 bit signed.
//!
//! The format is well documented; the canonical reference is the
//! Microsoft RIFF specification. We write the smallest legal file:
//! `RIFF` + `WAVE` + `fmt ` (16 byte PCM chunk) + `data`.
//!
//! Float inputs are clamped to `[-1, 1]` and scaled to the 16 bit
//! range. Any sample outside the clamp is silently saturated.

use std::fs::File;
use std::io::{BufWriter, Result as IoResult, Write};
use std::path::Path;

/// Write a mono PCM 16 file. `sample_rate_hz` is stored verbatim;
/// the function does not resample.
pub fn write_mono_pcm16(
    path: impl AsRef<Path>,
    samples: &[f32],
    sample_rate_hz: u32,
) -> IoResult<()> {
    let file = File::create(path)?;
    let mut w = BufWriter::new(file);

    let bits_per_sample: u16 = 16;
    let channels: u16 = 1;
    let byte_rate = sample_rate_hz * channels as u32 * bits_per_sample as u32 / 8;
    let block_align = channels * bits_per_sample / 8;
    let data_size = (samples.len() as u32) * (bits_per_sample as u32 / 8);
    let riff_size = 36 + data_size;

    w.write_all(b"RIFF")?;
    w.write_all(&riff_size.to_le_bytes())?;
    w.write_all(b"WAVE")?;

    w.write_all(b"fmt ")?;
    w.write_all(&16u32.to_le_bytes())?;
    w.write_all(&1u16.to_le_bytes())?; // PCM
    w.write_all(&channels.to_le_bytes())?;
    w.write_all(&sample_rate_hz.to_le_bytes())?;
    w.write_all(&byte_rate.to_le_bytes())?;
    w.write_all(&block_align.to_le_bytes())?;
    w.write_all(&bits_per_sample.to_le_bytes())?;

    w.write_all(b"data")?;
    w.write_all(&data_size.to_le_bytes())?;

    for &s in samples {
        let clamped = s.clamp(-1.0, 1.0);
        let q = (clamped * 32767.0).round() as i16;
        w.write_all(&q.to_le_bytes())?;
    }
    w.flush()?;
    Ok(())
}