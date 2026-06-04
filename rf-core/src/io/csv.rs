//! Tiny CSV writers. Two flavours: scalar column and pair column.
//! Used by examples to dump spectra and IQ traces for offline plot.

use std::fs::File;
use std::io::{BufWriter, Result as IoResult, Write};
use std::path::Path;

/// One column of scalar values with an `index, value` header.
pub fn write_scalar_csv(path: impl AsRef<Path>, label: &str, values: &[f32]) -> IoResult<()> {
    let file = File::create(path)?;
    let mut w = BufWriter::new(file);
    writeln!(w, "index,{label}")?;
    for (i, v) in values.iter().enumerate() {
        writeln!(w, "{i},{v}")?;
    }
    Ok(())
}

/// Two columns: `(a, b)` pairs. Useful for IQ dumps and for
/// frequency, magnitude tables produced by the FFT example.
pub fn write_pair_csv(
    path: impl AsRef<Path>,
    label_a: &str,
    label_b: &str,
    pairs: &[(f32, f32)],
) -> IoResult<()> {
    let file = File::create(path)?;
    let mut w = BufWriter::new(file);
    writeln!(w, "{label_a},{label_b}")?;
    for (a, b) in pairs {
        writeln!(w, "{a},{b}")?;
    }
    Ok(())
}