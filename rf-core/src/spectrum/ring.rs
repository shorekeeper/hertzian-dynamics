//! Fixed capacity audio ring buffer.
//!
//! Producers write PCM samples through `write`, consumers read
//! through `read`. The buffer holds at most `capacity` samples; an
//! overwrite drops the oldest data, which is the correct behaviour
//! for a live audio feed where stalling the producer would be
//! worse than losing a few milliseconds of audio.
//!

/// Simple ring buffer with overwrite-on-full semantics.
#[derive(Clone, Debug)]
pub struct AudioRing {
    buffer: Vec<f32>,
    write_pos: usize,
    read_pos: usize,
    available: usize,
}

impl AudioRing {
    /// New ring with the given capacity. `capacity` must be at
    /// least one; the constructor panics otherwise because a zero
    /// capacity ring is always a bug.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "AudioRing capacity must be positive");
        Self {
            buffer: vec![0.0; capacity],
            write_pos: 0,
            read_pos: 0,
            available: 0,
        }
    }

    /// Total number of slots in the buffer.
    pub fn capacity(&self) -> usize {
        self.buffer.len()
    }

    /// Samples currently available for reading.
    pub fn available(&self) -> usize {
        self.available
    }

    /// True if the buffer holds no readable samples.
    pub fn is_empty(&self) -> bool {
        self.available == 0
    }

    /// Append samples. When the buffer is full, the oldest samples
    /// are overwritten in order and `read_pos` is advanced to match.
    /// Returns the number of samples actually written (always
    /// `samples.len()`; the function never blocks or short writes).
    pub fn write(&mut self, samples: &[f32]) -> usize {
        let cap = self.buffer.len();
        for &s in samples {
            self.buffer[self.write_pos] = s;
            self.write_pos = (self.write_pos + 1) % cap;
            if self.available == cap {
                // Overwrite: bump the reader past the lost sample.
                self.read_pos = (self.read_pos + 1) % cap;
            } else {
                self.available += 1;
            }
        }
        samples.len()
    }

    /// Read up to `out.len()` samples. Any positions in `out` past
    /// the available count are filled with zero (a fill of silence
    /// is the right behaviour for downstream modulators, which
    /// expect a continuous PCM stream). Returns the number of
    /// real samples read; the rest of `out` is zero fill.
    pub fn read(&mut self, out: &mut [f32]) -> usize {
        let cap = self.buffer.len();
        let n = out.len().min(self.available);
        for slot in out.iter_mut().take(n) {
            *slot = self.buffer[self.read_pos];
            self.read_pos = (self.read_pos + 1) % cap;
        }
        for slot in out.iter_mut().skip(n) {
            *slot = 0.0;
        }
        self.available -= n;
        n
    }

    /// Discard all samples. Used when a transmitter goes off air
    /// and the manager needs to reset its state before reuse.
    pub fn clear(&mut self) {
        self.write_pos = 0;
        self.read_pos = 0;
        self.available = 0;
    }
}