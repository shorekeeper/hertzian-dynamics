//! Supported modulation modes.
//!
//! The discriminant is part of the GPU contract because compute
//! kernels in code will branch on it through a push constant
//! or storage buffer field. Never reorder existing variants. Add new
//! ones at the end with the next free integer.

/// Enumeration of modulations the engine knows how to produce and
/// demodulate.
#[repr(u32)]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Hash)]
pub enum Modulation {
    /// Continuous wave, unmodulated carrier. Beacons and morse.
    Cw = 0,
    /// Double sideband amplitude modulation with carrier. The classic
    /// broadcast format on LW, MW, SW bands.
    Am = 1,
    /// Narrow band frequency modulation. Voice quality, deviation
    /// roughly 2.5 to 5 kHz, channel width 12.5 to 25 kHz.
    NFm = 2,
    /// Wide band frequency modulation. Broadcast quality in the real
    /// world uses 75 kHz deviation and 200 kHz channel spacing in the
    /// 88 to 108 MHz band. The engine runs every stage at one 48 kHz
    /// sample rate, whose 24 kHz Nyquist limit cannot carry a 75 kHz
    /// excursion, so the modulator approximates wideband FM with 15 kHz
    /// deviation (see EmissionModulator::default_for). The mode
    /// therefore behaves like a wider narrowband FM rather than true
    /// broadcast FM; raising the engine sample rate is the only way to
    /// model the real deviation and is deferred for its CPU cost.
    WFm = 3,
    /// Upper sideband, suppressed carrier. Amateur HF voice.
    Usb = 4,
    /// Lower sideband, suppressed carrier. Amateur HF voice below
    /// 10 MHz by convention.
    Lsb = 5,
    /// Wideband noise. Used by jammers to deny a portion of spectrum.
    Noise = 6,
}

impl Modulation {
    /// Raw integer discriminant, intended for shader uniforms or
    /// network serialisation.
    #[inline]
    pub const fn as_u32(self) -> u32 {
        self as u32
    }

    /// Reverse mapping from raw integer. Returns `None` for any
    /// value not currently assigned to a variant.
    pub const fn from_u32(value: u32) -> Option<Self> {
        match value {
            0 => Some(Self::Cw),
            1 => Some(Self::Am),
            2 => Some(Self::NFm),
            3 => Some(Self::WFm),
            4 => Some(Self::Usb),
            5 => Some(Self::Lsb),
            6 => Some(Self::Noise),
            _ => None,
        }
    }
}