//! Spectrum manager facade.
//!
//! The single entry point higher layers (the future JNI bridge,
//! tests, examples) use to interact with the radio ether. The
//! manager owns the live emissions, the registered receivers, the
//! mixer scratch state and the Rayleigh fader. The propagation
//! solver, the voxel grid, the material table and the ionosphere
//! LUT remain borrowed: they outlive the manager and may be
//! shared with other consumers.

use std::collections::HashMap;

use crate::propagation::{IonosphereLut, MaterialTable, PropagationSolver, RayleighFading, SolverConfig};
use crate::propagation::voxel::VoxelGrid;
use crate::spectrum::emission::ActiveEmission;
use crate::spectrum::mixer::InterferenceMixer;
use crate::spectrum::receiver::{Receiver, ReceiverConfig};
use crate::spectrum::ENGINE_SAMPLE_RATE_HZ;
use crate::types::emission::EmissionId;
use crate::types::spectrum::{SpectrumChunk, SpectrumChunkHeader};

/// Opaque identifier of a registered receiver.
#[repr(transparent)]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct ReceiverId(pub u32);

/// Construction parameters for the manager.
#[derive(Copy, Clone, Debug)]
pub struct SpectrumManagerConfig {
    /// Engine sample rate, in hertz. Stored so the manager can
    /// hand it to new receivers; defaults to
    /// `ENGINE_SAMPLE_RATE_HZ`.
    pub sample_rate_hz: f32,
    /// Seed for the internal thermal noise generator. Fixed at
    /// construction so tests are reproducible.
    pub noise_seed: u64,
    /// Seed for the Rayleigh fader. Same reasoning.
    pub fading_seed: u64,
    /// Fading coherence factor. Zero gives uncorrelated draws,
    /// 0.95 gives a slow lazy fade.
    pub fading_coherence: f32,
}

impl Default for SpectrumManagerConfig {
    fn default() -> Self {
        Self {
            sample_rate_hz: ENGINE_SAMPLE_RATE_HZ,
            noise_seed: 0x1234_5678_9ABC_DEF0,
            fading_seed: 0x0FED_CBA9_8765_4321,
            fading_coherence: 0.9,
        }
    }
}

/// Spectrum manager. Owns the live state of the radio ether.
pub struct SpectrumManager {
    config: SpectrumManagerConfig,
    next_emission_id: u32,
    next_receiver_id: u32,
    emissions: HashMap<EmissionId, ActiveEmission>,
    receivers: HashMap<ReceiverId, Receiver>,
    mixer: InterferenceMixer,
    fading: RayleighFading,
    /// Solver configuration applied by mix_chunk. Stored so the FFI
    /// layer can set the curvature parameters once and have every later
    /// mix use them, rather than passing a config in on every call.
    solver_config: SolverConfig,
}

impl SpectrumManager {
    /// Construct an empty manager.
    pub fn new(config: SpectrumManagerConfig) -> Self {
        let mixer = InterferenceMixer::new(config.sample_rate_hz, config.noise_seed);
        let fading = RayleighFading::new(config.fading_seed, config.fading_coherence);
        Self {
            config,
            next_emission_id: 1,
            next_receiver_id: 1,
            emissions: HashMap::new(),
            receivers: HashMap::new(),
            mixer,
            fading,
            solver_config: SolverConfig::default(),
        }
    }

    /// Engine sample rate.
    pub fn sample_rate_hz(&self) -> f32 {
        self.config.sample_rate_hz
    }

    /// Current solver configuration. Copied out cheaply (it is Copy).
    pub fn solver_config(&self) -> SolverConfig {
        self.solver_config
    }

    /// Replace the solver configuration future mix calls will use. The
    /// FFI curvature setter routes through here so a world can tune the
    /// radio horizon at runtime.
    pub fn set_solver_config(&mut self, cfg: SolverConfig) {
        self.solver_config = cfg;
    }

    /// Register a new emission. The descriptor's `id` field is
    /// ignored on input; the manager assigns a fresh id and writes
    /// it back into the stored copy.
    pub fn register_emission(&mut self, mut emission: ActiveEmission) -> EmissionId {
        let id = EmissionId(self.next_emission_id);
        self.next_emission_id = self.next_emission_id.wrapping_add(1).max(1);
        emission.descriptor.id = id;
        self.emissions.insert(id, emission);
        id
    }

    /// Remove an emission. Returns the removed state for caller
    /// inspection or drop. No-op if the id is unknown.
    pub fn unregister_emission(&mut self, id: EmissionId) -> Option<ActiveEmission> {
        self.emissions.remove(&id)
    }

    /// Borrow a registered emission.
    pub fn emission(&self, id: EmissionId) -> Option<&ActiveEmission> {
        self.emissions.get(&id)
    }

    /// Mutate a registered emission. Used by callers that need to
    /// push audio or change position.
    pub fn emission_mut(&mut self, id: EmissionId) -> Option<&mut ActiveEmission> {
        self.emissions.get_mut(&id)
    }

    /// Number of registered emissions.
    pub fn emission_count(&self) -> usize {
        self.emissions.len()
    }

    /// Iterate every emission id. The order is unspecified and
    /// changes when the underlying hash map is touched.
    pub fn emission_ids(&self) -> impl Iterator<Item = EmissionId> + '_ {
        self.emissions.keys().copied()
    }

    /// Register a new receiver against the engine sample rate.
    pub fn register_receiver(&mut self, config: ReceiverConfig) -> ReceiverId {
        let id = ReceiverId(self.next_receiver_id);
        self.next_receiver_id = self.next_receiver_id.wrapping_add(1).max(1);
        let receiver = Receiver::new(config, self.config.sample_rate_hz);
        self.receivers.insert(id, receiver);
        id
    }

    /// Remove a receiver. Returns the removed state. No-op on
    /// unknown ids.
    pub fn unregister_receiver(&mut self, id: ReceiverId) -> Option<Receiver> {
        self.receivers.remove(&id)
    }

    /// Borrow a registered receiver.
    pub fn receiver(&self, id: ReceiverId) -> Option<&Receiver> {
        self.receivers.get(&id)
    }

    /// Mutate a registered receiver.
    pub fn receiver_mut(&mut self, id: ReceiverId) -> Option<&mut Receiver> {
        self.receivers.get_mut(&id)
    }

    /// Mix one chunk for one receiver against every live emission.
    /// The chunk buffer is allocated by the caller; `sample_count`
    /// inside the header is the number of samples to produce, and
    /// `chunk.samples.len()` must match.
    ///
    /// `grid`, `materials`, `ionosphere` and `solver_config` are
    /// borrowed from the caller because the manager is policy free
    /// about world state.
    pub fn mix_chunk<G: VoxelGrid>(
        &mut self,
        receiver_id: ReceiverId,
        grid: &G,
        materials: &MaterialTable,
        ionosphere: &IonosphereLut,
        solver_config: SolverConfig,
        chunk: &mut SpectrumChunk,
        server_tick: u64,
        local_hour: f32,
    ) -> bool {
        let Some(receiver) = self.receivers.get_mut(&receiver_id) else {
            return false;
        };
        if chunk.samples.len() != chunk.header.sample_count as usize {
            return false;
        }
        let solver = PropagationSolver::new(grid, materials, ionosphere, solver_config);
        // The borrow checker would not let us hand a `HashMap`
        // iterator into the mixer while it also holds `&mut
        // receiver`, so we collect the emission ids into a Vec and
        // resolve each through `self.emissions` inside a closure.
        // A small allocation per mix call; future code versions may
        // replace this with a hand rolled iterator over the map's
        // raw slots.
        let ids: Vec<EmissionId> = self.emissions.keys().copied().collect();
        let mut em_refs: Vec<(EmissionId, &mut ActiveEmission)> = Vec::with_capacity(ids.len());
        for (k, v) in self.emissions.iter_mut() {
            em_refs.push((*k, v));
        }
        self.mixer.mix(
            receiver,
            em_refs.into_iter(),
            &solver,
            &mut self.fading,
            server_tick,
            chunk,
            local_hour,
        );
        true
    }

    /// Convenience: build a `SpectrumChunk` for the receiver at the
    /// given chunk size, mix into it, and return the chunk. Pre
    /// allocates exactly once per call; callers that mix at high
    /// rate should reuse a single chunk through `mix_chunk` instead.
    pub fn make_and_mix<G: VoxelGrid>(
        &mut self,
        receiver_id: ReceiverId,
        grid: &G,
        materials: &MaterialTable,
        ionosphere: &IonosphereLut,
        solver_config: SolverConfig,
        sample_count: u32,
        server_tick: u64,
        local_hour: f32,
    ) -> Option<SpectrumChunk> {
        let receiver = self.receivers.get(&receiver_id)?;
        let header = SpectrumChunkHeader {
            center_hz: receiver.tuned_hz(),
            sample_rate_hz: self.config.sample_rate_hz,
            bandwidth_hz: receiver.bandwidth_hz(),
            sample_count,
            sequence: 0,
            server_tick,
            noise_floor_w: 0.0,
            signal_power_w: 0.0,
        };
        let mut chunk = SpectrumChunk::allocate(header);
        let ok = self.mix_chunk(
            receiver_id,
            grid,
            materials,
            ionosphere,
            solver_config,
            &mut chunk,
            server_tick,
            local_hour,
        );
        if ok {
            Some(chunk)
        } else {
            None
        }
    }
}