//! System noise floor model.
//!
//! The mixer needs the power of the noise the receiver sees so it
//! can inject a Gaussian floor before the AGC runs. That floor is
//! not the bare thermal limit: a real receiver adds its own noise
//! figure, and the antenna collects external noise from its
//! environment. This module turns a receiver noise figure, a tuned
//! frequency and an environment category into a single linear noise
//! factor that scales the thermal reference power k*T0*B.
//!
//! Composition
//!
//! Following ITU-R P.372, the total operating noise factor of a
//! receiving system with a lossless antenna and feedline is
//!
//!     F_total = F_external + F_receiver - 1
//!
//! where F_receiver is the receiver noise factor (the linear form of
//! the noise figure) and F_external is the antenna noise factor over
//! the thermal reference k*T0. The mixer multiplies k*T0*B by F_total
//! to obtain the operating noise power.
//!
//! External noise
//!
//! F_external is dominated below a few hundred MHz by man-made noise,
//! which P.372 models as a median figure falling with frequency:
//!
//!     Fam(dB) = c - d * log10(f_MHz)
//!
//! The (c, d) constants select the environment category. Below the
//! man-made curve sits the galactic background, modelled as
//! 52 - 23*log10(f_MHz) dB; the external factor takes whichever of
//! the two is larger at the queried frequency. Atmospheric (lightning)
//! noise is deliberately left out: it dominates only at LF and the
//! lower HF, is far too variable to bake into a static table, and the
//! engine's HF gameplay sits above the band where it would matter.
//!
//! Why this lives on the receiver
//!
//! Noise figure and environment both belong to the receiver, not to
//! the emission: a cheap handheld in a city has a worse floor than a
//! base station in open country listening to the same transmitter.
//! Both fields therefore live on ReceiverConfig and are read by the
//! mixer once per chunk. One consequence falls out for free: because
//! the external term grows toward HF, low bands become external noise
//! limited (the receiver figure barely moves the floor) while VHF
//! stays receiver limited, which is the real behaviour radios show.

/// Radio noise environment category. Selects the man-made noise
/// constants of the ITU-R P.372 median model. The discriminant is
/// part of the FFI contract (the Java side mirrors it); never reorder
/// existing variants, only append.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum NoiseEnvironment {
    /// Open country far from human activity. Quietest category; the
    /// floor here tracks the galactic background across most of HF.
    QuietRural,
    /// Farmland and sparse settlement.
    Rural,
    /// Suburban housing. The engine default: a middle ground that
    /// keeps VHF receivable while leaving HF audibly noisy.
    Residential,
    /// Dense urban and industrial areas. Highest man-made floor.
    City,
}

impl NoiseEnvironment {
    /// Raw integer discriminant for serialisation across the FFI
    /// boundary.
    pub const fn code(self) -> u32 {
        match self {
            Self::QuietRural => 0,
            Self::Rural => 1,
            Self::Residential => 2,
            Self::City => 3,
        }
    }

    /// Reverse mapping. Returns None for any value not assigned to a
    /// variant so the caller can fall back to a default.
    pub const fn from_code(code: u32) -> Option<Self> {
        match code {
            0 => Some(Self::QuietRural),
            1 => Some(Self::Rural),
            2 => Some(Self::Residential),
            3 => Some(Self::City),
            _ => None,
        }
    }

    /// The (c, d) constants of the man-made noise median curve
    /// Fam(dB) = c - d*log10(f_MHz), taken from ITU-R P.372.
    const fn man_made_constants(self) -> (f32, f32) {
        match self {
            Self::City => (76.8, 27.7),
            Self::Residential => (72.5, 27.7),
            Self::Rural => (67.2, 27.7),
            Self::QuietRural => (53.6, 28.6),
        }
    }
}

/// Lowest frequency, in MHz, the curves are characterised for.
/// Frequencies below this clamp to it rather than running log10 off
/// into the very large values it would produce near DC.
const MODEL_MIN_MHZ: f32 = 0.3;

/// Highest frequency, in MHz, the man-made curve is characterised
/// for. Above it the galactic floor and the receiver figure carry the
/// floor on their own.
const MODEL_MAX_MHZ: f32 = 250.0;

/// Median man-made noise factor in dB above the thermal reference,
/// for the given frequency and environment.
pub fn man_made_noise_factor_db(frequency_hz: f64, env: NoiseEnvironment) -> f32 {
    let f_mhz = ((frequency_hz / 1.0e6) as f32).clamp(MODEL_MIN_MHZ, MODEL_MAX_MHZ);
    let (c, d) = env.man_made_constants();
    c - d * f_mhz.log10()
}

/// Galactic background noise factor in dB above the thermal
/// reference. A quiet-sky median; the real value varies with pointing
/// and season but the median is enough for a floor.
pub fn galactic_noise_factor_db(frequency_hz: f64) -> f32 {
    let f_mhz = ((frequency_hz / 1.0e6) as f32).clamp(MODEL_MIN_MHZ, MODEL_MAX_MHZ);
    52.0 - 23.0 * f_mhz.log10()
}

/// External (antenna) noise factor as a linear ratio over the thermal
/// reference. The larger of the man-made and galactic contributions
/// wins at the queried frequency.
pub fn external_noise_factor(frequency_hz: f64, env: NoiseEnvironment) -> f32 {
    let fa_db = man_made_noise_factor_db(frequency_hz, env)
        .max(galactic_noise_factor_db(frequency_hz));
    10.0_f32.powf(fa_db / 10.0)
}

/// Total operating noise factor of the receiving system, linear.
/// Combines the receiver noise figure with the external noise the
/// antenna collects. A non finite noise figure falls back to an ideal
/// figure so the result is always defined, and the value is floored at
/// unity so the reported floor is never quieter than the thermal
/// reference (which keeps the downstream sigma well behaved).
pub fn system_noise_factor(noise_figure_db: f32, frequency_hz: f64, env: NoiseEnvironment) -> f32 {
    let nf = if noise_figure_db.is_finite() {
        noise_figure_db.clamp(0.0, 60.0)
    } else {
        0.0
    };
    let f_receiver = 10.0_f32.powf(nf / 10.0);
    let f_external = external_noise_factor(frequency_hz, env);
    (f_receiver + f_external - 1.0).max(1.0)
}