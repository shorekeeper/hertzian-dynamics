//! Spectrum chunks: the unit of work the interference mixer produces
//! for each active receiver.
//!
//! A receiver issues a request describing its tuned carrier, filter
//! bandwidth and desired sample count. The mixer returns a chunk
//! holding `sample_count` baseband IQ samples already filtered to
//! the receiver bandwidth and with all reachable emissions summed
//! in complex form. Demodulators consume the chunk and
//! produce audio PCM.

use crate::types::iq::Iq;

/// Fixed size header describing the contents of a spectrum chunk.
///
/// Kept separate from the sample payload so that several chunks for
/// the same batch upload can share contiguous backing storage in a
/// later versions without splitting headers from samples.
#[repr(C)]
#[derive(Copy, Clone, Debug)]
pub struct SpectrumChunkHeader {
    /// Receiver tuned frequency in hertz. Diagnostic and resync use.
    pub center_hz: f64,
    /// Sample rate of the baseband samples that follow, in hertz.
    pub sample_rate_hz: f32,
    /// Receiver filter bandwidth, full width, in hertz. Always
    /// less than or equal to `sample_rate_hz`.
    pub bandwidth_hz: f32,
    /// Number of `Iq` samples in the payload.
    pub sample_count: u32,
    /// Monotonic chunk sequence number for this receiver. Wraps
    /// after roughly 2^32 chunks which at 50 chunks per second is
    /// over two years of continuous tuning.
    pub sequence: u32,
    /// Server tick of the first sample. The audio pipeline uses
    /// this to detect drift and to drop or duplicate samples in
    /// case of clock skew.
    pub server_tick: u64,
    /// Estimated noise floor power in watts (linear), used by
    /// demodulators as the AGC and squelch reference.
    pub noise_floor_w: f32,
    /// Pre-AGC, pre-noise received signal power in watts (linear),
    /// summed across every emission that reached the receiver. Paired
    /// with noise_floor_w it gives the signal to noise ratio the client
    /// S-meter needs, which the post-AGC sample magnitudes cannot
    /// provide because the AGC normalises them toward a fixed target
    /// regardless of how strong the signal was. This slot was the former
    /// _pad reserve, so it stays 4 bytes and the 40 byte header layout,
    /// along with its GPU std430 mirror, is unchanged.
    pub signal_power_w: f32,
}

/// Owning chunk: header plus a heap allocated slice of samples.
///
/// `Box<[Iq]>` is preferred over `Vec<Iq>` because the length is
/// fixed at construction and there is no use case for resizing.
#[derive(Debug)]
pub struct SpectrumChunk {
    /// Chunk metadata.
    pub header: SpectrumChunkHeader,
    /// Baseband samples, exactly `header.sample_count` of them.
    pub samples: Box<[Iq]>,
}

impl SpectrumChunk {
    /// Allocate a chunk with `header.sample_count` zeroed samples.
    /// The header is taken by value and stored verbatim. This is the
    /// only path that allocates the sample slice, so size mismatches
    /// between header and payload cannot happen by construction.
    pub fn allocate(header: SpectrumChunkHeader) -> Self {
        let n = header.sample_count as usize;
        let samples = vec![Iq::ZERO; n].into_boxed_slice();
        Self { header, samples }
    }
}

// Layout guard.
const _: () = {
    assert!(core::mem::size_of::<SpectrumChunkHeader>() == 40);
    assert!(core::mem::align_of::<SpectrumChunkHeader>() == 8);
};