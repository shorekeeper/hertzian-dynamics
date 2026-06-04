//! Active emission: descriptor plus modulator plus content source.
//!
//! The manager holds one of these per live transmitter. The mixer
//! pulls baseband chunks from it through `produce_baseband`, which
//! is the only method that touches the modulator state. Frequency
//! offset and Doppler are applied later, per receiver, by the
//! mixer.
//!
//! Per-tick baseband cache
//! ----------------------------------------
//!
//! A single server tick routinely contains two or more receivers
//! consuming the same emission:
//!
//!   * a stationary radio receiver tile;
//!   * the hidden receiver the spectrum analyzer tile registers;
//!   * any handheld radios held by players in range.
//!
//! Draining the audio ring and advancing the modulator on every
//! `produce_baseband` call cannot serve that fan-out. The first
//! receiver in the mixer's emission loop drains the ring, and later
//! receivers receive zero-padded audio that the FM modulator turns
//! into a constant-phase carrier whose phase has already been
//! displaced by the prior call's audio integral. The result is a
//! mirrored aliasing pattern in the spectrum analyzer waterfall that
//! appears only when at least one real receiver is present and that
//! tracks the tick boundary exactly, because the phase jumps at each
//! new tick.
//!
//! The cache decouples production from consumption. `cached_baseband`
//! stores the samples produced during the current server tick; later
//! callers within the same tick receive a copy clipped to their
//! requested length. The modulator advances exactly once per tick,
//! sized to the largest request any consumer made, so the audio ring
//! drains at the same rate the transmitter fills it and every
//! receiver sees identical baseband for any given (emission, tick)
//! pair.

use crate::dsp::modulation::{
    AmModulator, AudioModulator, BasebandSource, CwModulator, FmModulator, NoiseJammer,
    Sideband, SsbModulator,
};
use crate::spectrum::ring::AudioRing;
use crate::types::emission::Emission;
use crate::types::iq::Iq;

/// Maximum baseband chunk the manager will ever request in one
/// call. Sized for 50 ms at the engine rate plus some headroom.
const MAX_CHUNK_SAMPLES: usize = 4096;

/// Sentinel meaning "no tick has been cached yet". Real server
/// ticks are seeded by the world tick counter, which at the
/// 20 Hz Minecraft rate would take roughly 29 billion years to
/// reach `u64::MAX`, so the sentinel can never collide with a
/// legitimate tick value.
const NEVER_CACHED: u64 = u64::MAX;

/// Audio source associated with an emission.
///
/// Voice transmitters store incoming PCM in a ring buffer; jammers
/// generate their own samples and need no external input. Future
/// content kinds (jukebox stream, beacon ID, packet modem) plug in
/// here.
#[derive(Debug)]
pub enum EmissionContent {
    /// PCM ring buffer fed by an external producer.
    Audio(AudioRing),
    /// Self generating noise source.
    Jam(NoiseJammer),
}

/// Modulator owned by the emission.
///
/// Each variant carries the full modulator state. The mixer never
/// reaches inside; it just calls `produce_baseband` on the
/// `ActiveEmission`, which dispatches to the variant.
#[derive(Debug)]
pub enum EmissionModulator {
    /// Continuous wave, key state in [0, 1].
    Cw(CwModulator),
    /// Amplitude modulation with carrier.
    Am(AmModulator),
    /// Frequency modulation.
    Fm(FmModulator),
    /// Single sideband, upper.
    SsbUpper(SsbModulator),
    /// Single sideband, lower.
    SsbLower(SsbModulator),
    /// Marker variant for jammers. The mixer skips audio modulation
    /// and pulls samples directly from the noise source.
    Noise,
}

impl EmissionModulator {
    /// Build a sensible default modulator for a given engine
    /// configuration. Used by the manager when the JNI layer asks
    /// for "the standard NFM" without specifying every parameter.
    pub fn default_for(kind: EmissionModulatorKind, sample_rate_hz: f32) -> Self {
        match kind {
            EmissionModulatorKind::Cw => Self::Cw(CwModulator::new(0.005, sample_rate_hz)),
            EmissionModulatorKind::Am => Self::Am(AmModulator::new(0.8)),
            EmissionModulatorKind::NarrowFm => {
                Self::Fm(FmModulator::new(5_000.0, sample_rate_hz))
            }
            EmissionModulatorKind::WideFm => {
                Self::Fm(FmModulator::new(15_000.0, sample_rate_hz))
            }
            EmissionModulatorKind::SsbUpper => Self::SsbUpper(SsbModulator::new(Sideband::Upper, 127)),
            EmissionModulatorKind::SsbLower => Self::SsbLower(SsbModulator::new(Sideband::Lower, 127)),
            EmissionModulatorKind::Noise => Self::Noise,
        }
    }
}

/// Friendly enumeration for the JNI layer to pick a modulator
/// kind without instantiating one. The manager calls
/// `EmissionModulator::default_for` on the chosen kind.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum EmissionModulatorKind {
    /// CW keying.
    Cw,
    /// Standard broadcast AM.
    Am,
    /// Narrowband FM voice, 5 kHz deviation.
    NarrowFm,
    /// Approximated wideband FM, 15 kHz deviation.
    WideFm,
    /// Upper sideband SSB.
    SsbUpper,
    /// Lower sideband SSB.
    SsbLower,
    /// Wideband noise jammer.
    Noise,
}

/// Active emission state.
#[derive(Debug)]
pub struct ActiveEmission {
    /// RF descriptor: position, carrier, power, antenna gain.
    pub descriptor: Emission,
    /// Velocity in m/s. Stored separately from `descriptor`
    /// because the descriptor is the JNI contract; velocity may
    /// update at every server tick.
    pub velocity: [f32; 3],
    /// Audio source (PCM ring or jammer).
    pub content: EmissionContent,
    /// Modulator state.
    pub modulator: EmissionModulator,
    /// Scratch buffer for PCM pulled out of `content` before
    /// modulation. Reused across calls to avoid heap traffic.
    pcm_scratch: Vec<f32>,
    /// Cached baseband produced for the current server tick. The
    /// first receiver to call `produce_baseband` during a tick
    /// fills this buffer; subsequent callers within the same tick
    /// read from it. The cache grows on demand when a later caller
    /// requests more samples than the earlier one did.
    cached_baseband: Vec<Iq>,
    /// Server tick the cache currently corresponds to. A different
    /// tick triggers a fresh fill on the next `produce_baseband`
    /// call; the sentinel `NEVER_CACHED` ensures the very first
    /// call after construction always produces fresh data.
    cached_tick: u64,
}

impl ActiveEmission {
    /// Build a new active emission. The PCM scratch buffer is
    /// pre-allocated at `MAX_CHUNK_SAMPLES`; calls that request
    /// more than that are clamped.
    pub fn new(
        descriptor: Emission,
        velocity: [f32; 3],
        content: EmissionContent,
        modulator: EmissionModulator,
    ) -> Self {
        Self {
            descriptor,
            velocity,
            content,
            modulator,
            pcm_scratch: vec![0.0; MAX_CHUNK_SAMPLES],
            cached_baseband: Vec::with_capacity(MAX_CHUNK_SAMPLES),
            cached_tick: NEVER_CACHED,
        }
    }

    /// Convenience: an audio emission with a ring of the given
    /// PCM capacity, using the default modulator for the chosen
    /// kind.
    pub fn audio(
        descriptor: Emission,
        velocity: [f32; 3],
        modulator_kind: EmissionModulatorKind,
        pcm_capacity: usize,
        sample_rate_hz: f32,
    ) -> Self {
        let content = EmissionContent::Audio(AudioRing::new(pcm_capacity));
        let modulator = EmissionModulator::default_for(modulator_kind, sample_rate_hz);
        Self::new(descriptor, velocity, content, modulator)
    }

    /// Convenience: a noise jammer emission.
    pub fn jammer(
        descriptor: Emission,
        velocity: [f32; 3],
        jammer: NoiseJammer,
    ) -> Self {
        Self::new(
            descriptor,
            velocity,
            EmissionContent::Jam(jammer),
            EmissionModulator::Noise,
        )
    }

    /// Append PCM samples to the audio ring. No effect on jammer
    /// emissions; the manager will not call this path for them.
    pub fn push_audio(&mut self, samples: &[f32]) {
        if let EmissionContent::Audio(ring) = &mut self.content {
            ring.write(samples);
        }
    }

    /// Number of unread PCM samples in the audio ring; zero for
    /// jammers.
    pub fn audio_available(&self) -> usize {
        match &self.content {
            EmissionContent::Audio(ring) => ring.available(),
            EmissionContent::Jam(_) => 0,
        }
    }

    /// Produce `out.len()` baseband samples on the emission's
    /// carrier reference (carrier at DC). The chunk length is
    /// clamped to `MAX_CHUNK_SAMPLES`; the rest of `out` is filled
    /// with `Iq::ZERO`.
    ///
    /// `server_tick` keys the per-tick baseband cache. Two
    /// receivers passing the same `server_tick` see identical
    /// baseband for any given emission; the modulator state and
    /// audio ring advance once for the largest request inside the
    /// tick rather than once per consumer.
    pub fn produce_baseband(&mut self, out: &mut [Iq], server_tick: u64) {
        let requested_n = out.len().min(MAX_CHUNK_SAMPLES);

        // Tick rollover: drop the previous tick's cache. The
        // capacity stays allocated so the next fill reuses the
        // same heap allocation.
        if self.cached_tick != server_tick {
            self.cached_baseband.clear();
            self.cached_tick = server_tick;
        }

        // Extend the cache when a later caller in the same tick
        // requests more samples than the earlier one did. The
        // modulator and ring advance by exactly `need` additional
        // samples; the existing cached prefix is left untouched
        // so earlier consumers' view of the spectrum stays
        // identical to what they saw.
        if self.cached_baseband.len() < requested_n {
            let have = self.cached_baseband.len();
            let need = requested_n - have;
            // Produce into a temporary because `cached_baseband`
            // is borrowed immutably by the if-check above and the
            // borrow checker would refuse the simultaneous mutable
            // borrow that `produce_fresh` requires through &mut
            // self. The temporary's heap allocation is one Vec per
            // tick per emission in the worst case, well below the
            // mix call's own bookkeeping cost.
            let mut tmp = vec![Iq::ZERO; need];
            self.produce_fresh(&mut tmp);
            self.cached_baseband.extend_from_slice(&tmp);
        }

        // Serve from cache. Trailing positions outside the cached
        // range receive Iq::ZERO, matching the original
        // produce_baseband contract.
        let (head, tail) = out.split_at_mut(requested_n);
        head.copy_from_slice(&self.cached_baseband[..requested_n]);
        for slot in tail.iter_mut() {
            *slot = Iq::ZERO;
        }
    }

    /// Produce `head.len()` fresh baseband samples without
    /// consulting the cache. Used by `produce_baseband` to extend
    /// the cache when a later consumer requests more samples than
    /// any prior consumer did in the same tick.
    ///
    /// The split between `produce_baseband` and `produce_fresh`
    /// lets the cache logic stay in one place while the
    /// modulation dispatch (which needs simultaneous &mut on
    /// `content` and `modulator`) remains a single match arm.
    fn produce_fresh(&mut self, head: &mut [Iq]) {
        let n = head.len();
        if n == 0 {
            return;
        }
        // PCM scratch grows on demand. The initial size of
        // MAX_CHUNK_SAMPLES covers the common case; this branch
        // protects against a hypothetical future caller asking
        // for more than the scratch was sized for at construction.
        if self.pcm_scratch.len() < n {
            self.pcm_scratch.resize(n, 0.0);
        }
        match (&mut self.content, &mut self.modulator) {
            (EmissionContent::Jam(jammer), EmissionModulator::Noise) => {
                jammer.generate(head);
            }
            (EmissionContent::Audio(ring), modulator) => {
                let pcm = &mut self.pcm_scratch[..n];
                ring.read(pcm);
                match modulator {
                    EmissionModulator::Cw(m) => m.modulate(pcm, head),
                    EmissionModulator::Am(m) => m.modulate(pcm, head),
                    EmissionModulator::Fm(m) => m.modulate(pcm, head),
                    EmissionModulator::SsbUpper(m) => m.modulate(pcm, head),
                    EmissionModulator::SsbLower(m) => m.modulate(pcm, head),
                    EmissionModulator::Noise => {
                        // Audio content with Noise modulator is a
                        // configuration mistake; silence the output
                        // rather than panic so the rest of the world
                        // keeps running.
                        for slot in head.iter_mut() {
                            *slot = Iq::ZERO;
                        }
                    }
                }
            }
            (EmissionContent::Jam(_), _) => {
                // Mismatched configuration; fall through to silence
                // for the same reason as above.
                for slot in head.iter_mut() {
                    *slot = Iq::ZERO;
                }
            }
        }
    }

    /// Reset modulator and AGC-touching state. The audio ring is
    /// cleared as a side effect so a re-keyed transmitter starts
    /// without stale samples. The per-tick baseband cache is also
    /// discarded; the next `produce_baseband` will produce fresh
    /// samples even within the same tick.
    pub fn reset(&mut self) {
        match &mut self.modulator {
            EmissionModulator::Cw(m) => m.reset(),
            EmissionModulator::Am(m) => m.reset(),
            EmissionModulator::Fm(m) => m.reset(),
            EmissionModulator::SsbUpper(m) => m.reset(),
            EmissionModulator::SsbLower(m) => m.reset(),
            EmissionModulator::Noise => {}
        }
        match &mut self.content {
            EmissionContent::Audio(ring) => ring.clear(),
            EmissionContent::Jam(jammer) => jammer.reset(),
        }
        self.cached_baseband.clear();
        self.cached_tick = NEVER_CACHED;
    }
}