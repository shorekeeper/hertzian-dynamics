//! Single pole DC blocker.
//!
//! Difference equation: `y[n] = x[n] - x[n - 1] + r * y[n - 1]`,
//! with `r` close to but less than one. The frequency response has a
//! zero at DC and a pole on the real axis near unity, so it acts as
//! a very narrow high pass. Standard utility downstream of envelope
//! detectors and at the head of microphone capture paths.

/// DC blocking filter, real input only.
#[derive(Copy, Clone, Debug)]
pub struct DcBlocker {
    r: f32,
    x_prev: f32,
    y_prev: f32,
}

impl DcBlocker {
    /// `r` controls the cutoff: `fc ~ (1 - r) * fs / (2 pi)`. A
    /// value of `0.995` at 48 kHz yields roughly 38 Hz cutoff.
    pub fn new(r: f32) -> Self {
        debug_assert!(r > 0.0 && r < 1.0);
        Self { r, x_prev: 0.0, y_prev: 0.0 }
    }

    /// Reset history.
    pub fn reset(&mut self) {
        self.x_prev = 0.0;
        self.y_prev = 0.0;
    }

    /// Process one sample.
    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        let y = x - self.x_prev + self.r * self.y_prev;
        self.x_prev = x;
        self.y_prev = y;
        y
    }

    /// Block process.
    pub fn process_block(&mut self, samples: &mut [f32]) {
        for s in samples.iter_mut() {
            *s = self.process(*s);
        }
    }
}