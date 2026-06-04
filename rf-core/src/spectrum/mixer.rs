//! Interference mixer.
//!
//! For one receiver, walk every active emission, ask the
//! propagation solver for the link budget, frequency translate the
//! emission's baseband samples into the receiver passband, and sum
//! everything into the receiver's spectrum chunk. Add a thermal
//! noise floor, then run the chunk through the receiver AGC.
//!
//! Frequency translation
//! ---------------------
//!
//! An emission with carrier f_tx and Doppler delta_f, observed by a
//! receiver tuned to f_rx, contributes at the offset
//!     f_offset = f_tx - f_rx + delta_f.
//! At baseband each sample becomes
//!     y[n] = x[n] * exp(j 2 pi f_offset (n + t0) / fs)
//! where t0 is the running phase the receiver remembers for that
//! emission. We accumulate phase in `f64` because at fs = 48 kHz a
//! sustained 1 kHz offset wraps roughly 50 times per second and
//! `f32` phase loses precision after a few minutes.
//!
//! Out of band rejection
//! ---------------------
//!
//! An emission whose channel sits entirely outside the receiver
//! filter contributes nothing useful, only aliased copies. The
//! mixer rejects any emission whose centre frequency is more than
//! (rx_bw + tx_bw) / 2 away from the receiver tuning. This is the
//! standard "filter brick wall" approximation.

use crate::dsp::noise::GaussianNoise;
use crate::propagation::{PropagationInputs, PropagationSolver, RayleighFading};
use crate::propagation::voxel::VoxelGrid;
use crate::spectrum::emission::ActiveEmission;
use crate::spectrum::receiver::Receiver;
use crate::spectrum::noise_floor;
use crate::spectrum::THERMAL_NOISE_DENSITY_W_PER_HZ;
use crate::types::emission::EmissionId;
use crate::types::iq::Iq;
use crate::types::propagation::PathLoss;
use crate::types::spectrum::{SpectrumChunk, SpectrumChunkHeader};

/// Stateful mixer. Holds scratch buffers that get reused across
/// mix calls so the hot path stays allocation free.
pub struct InterferenceMixer {
    /// Buffer for the current emission baseband chunk.
    em_baseband: Vec<Iq>,
    /// Buffer for the noise contribution.
    noise_buf: Vec<Iq>,
    /// Gaussian noise generator for the thermal floor.
    noise: GaussianNoise,
    /// Engine sample rate.
    sample_rate_hz: f32,
}

impl InterferenceMixer {
    /// Build a mixer for the given sample rate. The `noise_seed`
    /// pins the thermal noise generator so tests are reproducible.
    pub fn new(sample_rate_hz: f32, noise_seed: u64) -> Self {
        Self {
            em_baseband: Vec::new(),
            noise_buf: Vec::new(),
            noise: GaussianNoise::new(noise_seed, 1.0),
            sample_rate_hz,
        }
    }

    /// Engine sample rate.
    pub fn sample_rate_hz(&self) -> f32 {
        self.sample_rate_hz
    }

    /// Mix one chunk for one receiver against the given emissions
    /// and propagation solver. The chunk samples are zeroed at the
    /// start. The header is written with the receiver tuning,
    /// sequence and a noise floor estimate.
    pub fn mix<'a, G, I>(
        &mut self,
        receiver: &mut Receiver,
        emissions: I,
        solver: &PropagationSolver<'a, G>,
        fading: &mut RayleighFading,
        server_tick: u64,
        chunk: &mut SpectrumChunk,
        local_hour: f32,
    ) where
        G: VoxelGrid,
        I: IntoIterator<Item = (EmissionId, &'a mut ActiveEmission)>,
    {
        let n = chunk.header.sample_count as usize;
        for s in chunk.samples.iter_mut() {
            *s = Iq::ZERO;
        }
        if self.em_baseband.len() != n {
            self.em_baseband.resize(n, Iq::ZERO);
        }
        if self.noise_buf.len() != n {
            self.noise_buf.resize(n, Iq::ZERO);
        }

        let rx_tuned = receiver.tuned_hz();
        let rx_bw = receiver.bandwidth_hz();
        let rx_pos = receiver.position();
        let rx_vel = receiver.velocity();
        let rx_gain = receiver.antenna_gain();

        // Live ids built during the loop, used by the offset
        // pruner after the loop ends.
        let mut live: Vec<EmissionId> = Vec::new();

        for (id, emission) in emissions {
            live.push(id);

            // Quick frequency check. Half-band edges of the
            // emission and the receiver: if the gap exceeds the
            // sum of the half-widths, the emission cannot
            // contribute anything in band.
            let centre_gap_hz = (emission.descriptor.carrier_hz - rx_tuned).abs();
            let half_sum = 0.5 * (rx_bw + emission.descriptor.bandwidth_hz) as f64;
            if centre_gap_hz > half_sum {
                continue;
            }

            let inputs = PropagationInputs {
                tx_pos: [
                    emission.descriptor.pos_x,
                    emission.descriptor.pos_y,
                    emission.descriptor.pos_z,
                ],
                rx_pos,
                tx_vel: emission.velocity,
                rx_vel,
                frequency_hz: emission.descriptor.carrier_hz,
                tx_gain: emission.descriptor.antenna_gain,
                rx_gain,
                local_hour,
            };
            let path: PathLoss = solver.solve(inputs, fading);
            if path.is_closed() {
                continue;
            }

            // Pull the emission baseband. server_tick keys the per-tick
            // cache inside ActiveEmission so multiple receivers in the
            // same tick share one production of audio rather than racing
            // to drain the audio ring.
            emission.produce_baseband(&mut self.em_baseband, server_tick);

            // The received amplitude scales by sqrt(linear power
            // ratio) and by sqrt(tx_power_w) so the engine numbers
            // come out in watt^(1/2) consistent units.
            let amplitude_gain = (path.linear * emission.descriptor.tx_power_w).sqrt();

            // Frequency offset including Doppler.
            let f_offset = (emission.descriptor.carrier_hz - rx_tuned) as f32 + path.doppler_hz;
            let phase_step = core::f64::consts::TAU * f_offset as f64
                / self.sample_rate_hz as f64;
            let mut phase = receiver.offset_phase(id);

            // Sum into the chunk.
            for (k, src) in self.em_baseband.iter().enumerate().take(n) {
                let lo = Iq {
                    i: phase.cos() as f32,
                    q: phase.sin() as f32,
                };
                let shifted = src.mul(lo).scale(amplitude_gain);
                chunk.samples[k] = chunk.samples[k].add(shifted);
                phase += phase_step;
                if phase > core::f64::consts::PI {
                    phase -= core::f64::consts::TAU;
                } else if phase < -core::f64::consts::PI {
                    phase += core::f64::consts::TAU;
                }
            }
            receiver.put_offset_phase(id, phase);
        }

        // Pre-AGC received signal power, summed across the emissions
        // mixed above and measured before the noise floor and the AGC
        // touch the chunk. Paired with the noise power below it yields
        // the true signal to noise ratio the client S-meter reports; the
        // post-AGC sample magnitudes cannot, because the AGC drives them
        // to a fixed target no matter how strong the signal actually was.
        let mut signal_acc = 0.0_f64;
        for s in chunk.samples.iter() {
            signal_acc += s.power() as f64;
        }
        let signal_power_w = (signal_acc / n.max(1) as f64) as f32;

        // System noise floor.
        //
        // The injected noise is the full operating noise of the
        // receiving system, not the bare thermal limit. Its power is
        // the thermal reference k*T0*B scaled by the total system
        // noise factor F_total, which combines the receiver noise
        // figure with the external noise the antenna collects from its
        // environment. The model lives in crate::spectrum::noise_floor;
        // here we only turn its factor into a per sample sigma.
        //
        // The complex Gaussian noise has independent I and Q rails,
        // each drawn N(0, sigma^2), so one sample carries power
        // E[I^2 + Q^2] = 2*sigma^2. To make the injected power match
        // the computed noise_power_w we set the per rail sigma to
        // sqrt(noise_power_w / 2). The same half power split is why
        // RayleighFading seeds its generator with 1/sqrt(2). The header
        // reports noise_power_w itself, the true total power, rather
        // than the per rail variance.
        let noise_factor = noise_floor::system_noise_factor(
            receiver.noise_figure_db(),
            rx_tuned,
            receiver.noise_environment(),
        );
        let noise_power_w = THERMAL_NOISE_DENSITY_W_PER_HZ * rx_bw.max(1.0) * noise_factor;
        let sigma = (0.5 * noise_power_w).sqrt();
        self.noise.set_sigma(sigma);
        self.noise.fill_iq(&mut self.noise_buf);
        for (s, n) in chunk.samples.iter_mut().zip(self.noise_buf.iter()) {
            *s = s.add(*n);
        }
        let noise_floor_w = noise_power_w;

        // AGC.
        receiver.agc_mut().process_block(&mut chunk.samples);

        // Header.
        chunk.header = SpectrumChunkHeader {
            center_hz: rx_tuned,
            sample_rate_hz: self.sample_rate_hz,
            bandwidth_hz: rx_bw,
            sample_count: n as u32,
            sequence: receiver.next_sequence(),
            server_tick,
            noise_floor_w,
            signal_power_w,
        };

        // Prune stale phase entries.
        let live_set: std::collections::HashSet<EmissionId> = live.into_iter().collect();
        receiver.prune_offsets(|id| live_set.contains(&id));
    }
}