//! Receiver state.
//!
//! A receiver carries the tuning, the filter bandwidth, the
//! per-emission frequency offset NCO phases, the AGC, and a
//! monotonic chunk sequence number. The mixer reads this state
//! through `&mut` and updates it in place once per mix call.

use std::collections::HashMap;

use crate::spectrum::agc::{Agc, AgcConfig};
use crate::types::emission::EmissionId;
use crate::types::modulation::Modulation;
use crate::spectrum::noise_floor::NoiseEnvironment;

/// Construction parameters.
#[derive(Copy, Clone, Debug)]
pub struct ReceiverConfig {
    /// Initial tuned carrier in hertz.
    pub tuned_hz: f64,
    /// Filter passband full width in hertz.
    pub bandwidth_hz: f32,
    /// Modulation expected by the user. The mixer does not look at
    /// this; it is carried alongside so the demodulator stage
    /// downstream picks the right path.
    pub modulation: Modulation,
    /// Receiver antenna gain over isotropic, linear.
    pub antenna_gain: f32,
    /// Receiver position in metres.
    pub position: [f32; 3],
    /// Receiver velocity in m/s.
    pub velocity: [f32; 3],
    /// AGC settings; the default is suitable for voice.
    pub agc: AgcConfig,
    /// Receiver noise figure in dB. The linear noise factor it implies
    /// is added to the external noise factor inside the mixer to form
    /// the operating noise floor. Consumer handheld and base receivers
    /// sit in the 10 to 20 dB range; the engine default is 12 dB.
    pub noise_figure_db: f32,
    /// Radio noise environment at the receiver site. Selects the
    /// man-made noise constants for the external noise factor: the
    /// same receiver hears a higher floor in a city than in open
    /// country listening to the same transmitter.
    pub noise_environment: NoiseEnvironment,
}

impl Default for ReceiverConfig {
    fn default() -> Self {
        Self {
            tuned_hz: 100.0e6,
            bandwidth_hz: 15_000.0,
            modulation: Modulation::NFm,
            antenna_gain: 1.0,
            position: [0.0; 3],
            velocity: [0.0; 3],
            agc: AgcConfig::default(),
            noise_figure_db: 12.0,
            noise_environment: NoiseEnvironment::Residential,
        }
    }
}

/// Per receiver runtime state.
pub struct Receiver {
    config: ReceiverConfig,
    /// Phase accumulator (radians) for the per-emission frequency
    /// offset NCO. Persists across mix calls so the offset cosine
    /// stays continuous from chunk to chunk.
    offset_phases: HashMap<EmissionId, f64>,
    agc: Agc,
    /// Monotonic sequence of mix calls. Wraps at 2^32, which at
    /// 50 chunks per second is over two years of operation.
    sequence: u32,
}

impl Receiver {
    /// Build a receiver against the engine sample rate.
    pub fn new(config: ReceiverConfig, sample_rate_hz: f32) -> Self {
        let agc = Agc::new(config.agc, sample_rate_hz);
        Self {
            config,
            offset_phases: HashMap::new(),
            agc,
            sequence: 0,
        }
    }

    /// Borrow the immutable configuration.
    pub fn config(&self) -> &ReceiverConfig {
        &self.config
    }

    /// Mutate tuning. The phase accumulators stay; if the receiver
    /// retunes by a large amount the existing offsets are now
    /// numerically meaningless, but they keep their continuity for
    /// any emission that is still in range, and stale entries get
    /// pruned in `prune_offsets`.
    pub fn set_tuned_hz(&mut self, hz: f64) {
        self.config.tuned_hz = hz;
    }

    /// Tuned carrier in hertz.
    pub fn tuned_hz(&self) -> f64 {
        self.config.tuned_hz
    }

    /// Filter passband full width in hertz.
    pub fn bandwidth_hz(&self) -> f32 {
        self.config.bandwidth_hz
    }

    /// Expected modulation; relevant to the demodulation stage.
    pub fn modulation(&self) -> Modulation {
        self.config.modulation
    }

    /// Receiver position in metres.
    pub fn position(&self) -> [f32; 3] {
        self.config.position
    }

    /// Receiver velocity in m/s.
    pub fn velocity(&self) -> [f32; 3] {
        self.config.velocity
    }

    /// Antenna gain over isotropic.
    pub fn antenna_gain(&self) -> f32 {
        self.config.antenna_gain
    }

    /// Receiver noise figure in dB. Read by the mixer once per chunk
    /// to size the operating noise floor.
    pub fn noise_figure_db(&self) -> f32 {
        self.config.noise_figure_db
    }

    /// Radio noise environment at the receiver site.
    pub fn noise_environment(&self) -> NoiseEnvironment {
        self.config.noise_environment
    }

    /// Borrow the AGC.
    pub fn agc(&self) -> &Agc {
        &self.agc
    }

    /// Borrow the AGC mutably for the mixer.
    pub fn agc_mut(&mut self) -> &mut Agc {
        &mut self.agc
    }

    /// Update the position and velocity. Useful for receivers
    /// attached to player held radios.
    pub fn set_position_velocity(&mut self, pos: [f32; 3], vel: [f32; 3]) {
        self.config.position = pos;
        self.config.velocity = vel;
    }

    /// Read or initialise the offset phase for one emission. The
    /// mixer calls this once per emission per mix step.
    pub fn offset_phase(&mut self, id: EmissionId) -> f64 {
        *self.offset_phases.entry(id).or_insert(0.0)
    }

    /// Store the updated offset phase.
    pub fn put_offset_phase(&mut self, id: EmissionId, phase: f64) {
        self.offset_phases.insert(id, phase);
    }

    /// Drop phase entries for emissions that no longer exist.
    /// Called once per mix call against the manager's live id set.
    pub fn prune_offsets<F: Fn(EmissionId) -> bool>(&mut self, is_live: F) {
        self.offset_phases.retain(|id, _| is_live(*id));
    }

    /// Next sequence number. Wraps naturally at 2^32.
    pub fn next_sequence(&mut self) -> u32 {
        let s = self.sequence;
        self.sequence = self.sequence.wrapping_add(1);
        s
    }
}