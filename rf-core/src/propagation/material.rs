//! Material database for voxel propagation.
//!
//! Each material is described by frequency dependent electrical
//! properties following the ITU-R P.2040 power law form: the real part
//! of the relative permittivity is
//!
//!     epsilon_r(f) = a * f^b
//!
//! and the conductivity, in siemens per metre, is
//!
//!     sigma(f) = c * f^d
//!
//! with f in gigahertz. The four coefficients (a, b, c, d) carry the
//! whole material model; setting b or d to zero pins the corresponding
//! quantity to a constant.
//!
//! Complex relative permittivity
//! -----------------------------
//!
//! A lossy dielectric is summarised by the complex relative permittivity
//!
//!     eta = epsilon_r - j * epsilon_r''
//!     epsilon_r'' = sigma / (omega * epsilon_0)
//!
//! where omega = 2*pi*f and epsilon_0 is the vacuum permittivity. With f
//! in hertz this is epsilon_r'' = sigma / (2*pi*f*epsilon_0). The same
//! eta drives both the per metre absorption used by the voxel raycast and
//! the Fresnel reflection coefficients the reflection model consumes, so
//! storing (a, b, c, d) here keeps the two consistent by construction.
//!
//! Attenuation from the propagation constant
//! -----------------------------------------
//!
//! A plane wave in the medium propagates as exp(-j*k*z) with the complex
//! wavenumber k = (omega/c)*sqrt(eta) = (omega/c)*(p - j*q). The real part
//! p sets the phase velocity; the imaginary part q sets the decay. Writing
//! the square root of a complex number in rectangular form,
//!
//!     q = sqrt( ( |eta| - epsilon_r ) / 2 ),   |eta| = sqrt(epsilon_r^2 + epsilon_r''^2)
//!
//! the field attenuation constant is
//!
//!     alpha = (omega / c) * q          [nepers per metre]
//!
//! and the power attenuation rate is alpha converted to decibels,
//!
//!     A = (20 / ln 10) * alpha = 8.6859 * alpha   [decibels per metre].
//!
//! This is exact for every loss tangent. In the good conductor limit
//! (sigma >> omega*epsilon_0*epsilon_r) it reduces to the skin effect
//! alpha ~ sqrt(pi*f*mu*sigma), growing as the square root of frequency.
//! In the low loss limit (sigma << omega*epsilon_0*epsilon_r) it reduces
//! to A ~ 1636 * sigma / sqrt(epsilon_r), the well known ITU-R P.2040
//! specific attenuation approximation, which is a useful sanity check on
//! the coefficient choices below.
//!
//! Coefficient provenance and frequency range
//! -------------------------------------------
//!
//! The functional form is from ITU-R P.2040, whose tabulated material
//! coefficients are characterised at and above roughly 1 GHz. The mod
//! operates from the low HF band up into the UHF band, well below that
//! range, so the coefficients here are chosen to behave sensibly across
//! HF, VHF and UHF rather than copied verbatim from the table; several
//! materials are pinned to constant permittivity and conductivity with
//! standard antenna engineering values where the published power law
//! would misbehave when extrapolated down. The per metre magnitudes are
//! gameplay grade engineering figures for 1 m solid voxels, not
//! laboratory measurements, and most are deliberately more transparent
//! than a naive intuition expects because real building materials pass
//! VHF readily (FM radio works indoors). Solid barriers in game come from
//! metal, water and reflection geometry rather than from dielectric loss.
//!
//! Each material carries a pivot frequency that clamps the property
//! evaluation frequency from below, so the power laws are never evaluated
//! deep outside their validity where epsilon_r or sigma would run away.
//! The geometric factors (the omega/c wavenumber term and the
//! omega*epsilon_0 divisor of epsilon_r'') always use the true operating
//! frequency; only the (a, b, c, d) evaluation is clamped.

/// Vacuum permittivity, farads per metre (CODATA).
const VACUUM_PERMITTIVITY_F_PER_M: f64 = 8.854_187_812_8e-12;

/// Speed of light in vacuum, metres per second.
const SPEED_OF_LIGHT_M_S: f64 = 299_792_458.0;

/// Conversion from nepers to decibels, 20 / ln(10).
const NEPER_TO_DB: f64 = 8.685_889_638_065_037;

/// Two pi, in f64.
const TAU_F64: f64 = std::f64::consts::TAU;

/// Stable identifier of a material in the table. The JNI layer hands
/// these back to Java as `int` indices; never reuse a value across schema
/// versions.
#[repr(transparent)]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct MaterialId(pub u16);

impl MaterialId {
    /// The empty voxel. Air does not absorb or reflect at the frequencies
    /// the engine covers, so this is always the lossless entry.
    pub const AIR: Self = Self(0);
}

/// Static material description.
///
/// The four power law coefficients are the whole electromagnetic model;
/// permittivity, conductivity, complex permittivity and attenuation are
/// all derived from them. See the module documentation for the formulas.
#[derive(Copy, Clone, Debug)]
pub struct Material {
    /// Identifier; equal to the index of this entry in the table.
    pub id: MaterialId,
    /// Stable display name for diagnostics.
    pub name: &'static str,
    /// Permittivity coefficient a in epsilon_r = a * f_GHz^b.
    pub eps_a: f32,
    /// Permittivity exponent b in epsilon_r = a * f_GHz^b.
    pub eps_b: f32,
    /// Conductivity coefficient c in sigma = c * f_GHz^d, siemens/metre.
    pub sigma_c: f32,
    /// Conductivity exponent d in sigma = c * f_GHz^d.
    pub sigma_d: f32,
    /// Lower clamp, in hertz, on the frequency used to evaluate the power
    /// laws. Guards against unphysical extrapolation below the model's
    /// validity. The wavenumber and loss tangent divisor still use the
    /// true operating frequency.
    pub pivot_frequency_hz: f32,
}

impl Material {
    /// Frequency, in hertz, used to evaluate the (a, b, c, d) power laws.
    /// Clamped from below by the pivot.
    fn eval_freq_hz(&self, frequency_hz: f64) -> f64 {
        frequency_hz.max(self.pivot_frequency_hz as f64)
    }

    /// Real part of the relative permittivity at a frequency.
    pub fn relative_permittivity(&self, frequency_hz: f64) -> f32 {
        let f_ghz = self.eval_freq_hz(frequency_hz) / 1.0e9;
        (self.eps_a as f64 * f_ghz.powf(self.eps_b as f64)) as f32
    }

    /// Conductivity at a frequency, in siemens per metre.
    pub fn conductivity(&self, frequency_hz: f64) -> f32 {
        let f_ghz = self.eval_freq_hz(frequency_hz) / 1.0e9;
        (self.sigma_c as f64 * f_ghz.powf(self.sigma_d as f64)) as f32
    }

    /// Complex relative permittivity at a frequency, returned as
    /// (epsilon_r, epsilon_r''). The imaginary part is the loss term
    /// sigma / (2*pi*f*epsilon_0) evaluated at the true operating
    /// frequency; the conductivity feeding it is clamped to the pivot.
    pub fn complex_permittivity(&self, frequency_hz: f64) -> (f32, f32) {
        let eps_r = self.relative_permittivity(frequency_hz) as f64;
        let sigma = self.conductivity(frequency_hz) as f64;
        let f = frequency_hz.max(1.0);
        let eps_imag = sigma / (TAU_F64 * f * VACUUM_PERMITTIVITY_F_PER_M);
        (eps_r as f32, eps_imag as f32)
    }

    /// Attenuation in decibels per metre at a frequency.
    ///
    /// Derived from the imaginary part of the plane wave propagation
    /// constant; see the module documentation. Air (epsilon_r at or below
    /// one with zero loss) returns exactly zero. A good conductor produces
    /// a very large value that the caller's budget then caps, which is the
    /// correct near total shielding behaviour for a solid metal voxel.
    pub fn attenuation_db_per_m(&self, frequency_hz: f64) -> f32 {
        let (eps_r, eps_imag) = self.complex_permittivity(frequency_hz);
        let eps_r = eps_r as f64;
        let eps_imag = eps_imag as f64;
        if eps_imag <= 0.0 && eps_r <= 1.0 {
            return 0.0;
        }
        let magnitude = (eps_r * eps_r + eps_imag * eps_imag).sqrt();
        let q = (0.5 * (magnitude - eps_r)).max(0.0).sqrt();
        let f = frequency_hz.max(1.0);
        let alpha_np_per_m = (TAU_F64 * f / SPEED_OF_LIGHT_M_S) * q;
        (NEPER_TO_DB * alpha_np_per_m) as f32
    }
}

/// Lookup table indexed by MaterialId. Stored as a Vec for cheap growth
/// at startup, then frozen by the time the solver runs.
#[derive(Clone, Debug, Default)]
pub struct MaterialTable {
    entries: Vec<Material>,
}

impl MaterialTable {
    /// Empty table. The caller must register AIR before doing any useful
    /// work; `with_defaults` does this and adds a handful of stock
    /// materials.
    pub fn new() -> Self {
        Self { entries: Vec::new() }
    }

    /// Build a table with the stock material set. The per material
    /// comments record the physical reasoning behind each coefficient
    /// choice; the verification figures quoted are the attenuation at
    /// 145 MHz, the engine's default VHF test band.
    pub fn with_defaults() -> Self {
        let mut t = Self::new();
        // Air. Vacuum: unit permittivity, no conductivity, lossless.
        t.register(Material {
            id: MaterialId(0),
            name: "air",
            eps_a: 1.0,
            eps_b: 0.0,
            sigma_c: 0.0,
            sigma_d: 0.0,
            pivot_frequency_hz: 1.0e6,
        });
        // Stone. Dense rock and concrete share epsilon_r near 5; the
        // conductivity grows as the square root of frequency, the skin
        // effect trend of a weakly conducting mineral. About 2.7 dB/m at
        // 145 MHz, a leaky barrier the way real concrete passes VHF.
        t.register(Material {
            id: MaterialId(1),
            name: "stone",
            eps_a: 5.3,
            eps_b: 0.0,
            sigma_c: 0.012,
            sigma_d: 0.5,
            pivot_frequency_hz: 1.0e6,
        });
        // Water. Fresh water at VHF has very high permittivity near 80 and
        // ionic conductivity that makes it strongly lossy; about 18 dB/m
        // at 145 MHz, a real obstacle and a good reflector.
        t.register(Material {
            id: MaterialId(2),
            name: "water",
            eps_a: 80.0,
            eps_b: 0.0,
            sigma_c: 0.1,
            sigma_d: 0.0,
            pivot_frequency_hz: 1.0e6,
        });
        // Wood. Dry low loss dielectric, permittivity near 2, square root
        // frequency conductivity. Under 1 dB/m at 145 MHz.
        t.register(Material {
            id: MaterialId(3),
            name: "wood",
            eps_a: 2.0,
            eps_b: 0.0,
            sigma_c: 0.002,
            sigma_d: 0.5,
            pivot_frequency_hz: 1.0e6,
        });
        // Glass. Low loss dielectric, permittivity near 6; essentially
        // transparent at VHF, a fraction of a dB per metre.
        t.register(Material {
            id: MaterialId(4),
            name: "glass",
            eps_a: 6.0,
            eps_b: 0.0,
            sigma_c: 0.001,
            sigma_d: 0.5,
            pivot_frequency_hz: 1.0e6,
        });
        // Dirt. Standard average ground, permittivity near 13 and
        // conductivity near 0.005 S/m held flat across HF to VHF, the
        // antenna engineering reference value. About 2.3 dB/m at 145 MHz.
        t.register(Material {
            id: MaterialId(5),
            name: "dirt",
            eps_a: 13.0,
            eps_b: 0.0,
            sigma_c: 0.005,
            sigma_d: 0.0,
            pivot_frequency_hz: 1.0e6,
        });
        // Leaves. Foliage is a light scatterer with permittivity near 1.5
        // and square root frequency loss; about 1 dB/m at 145 MHz.
        t.register(Material {
            id: MaterialId(6),
            name: "leaves",
            eps_a: 1.5,
            eps_b: 0.0,
            sigma_c: 0.002,
            sigma_d: 0.5,
            pivot_frequency_hz: 1.0e6,
        });
        // Iron. A solid metre of ferrous metal is a near perfect electric
        // conductor: a very large constant conductivity drives the
        // attenuation past any reasonable budget, so the voxel is opaque
        // and, for the reflection model, a near total reflector.
        t.register(Material {
            id: MaterialId(7),
            name: "iron",
            eps_a: 1.0,
            eps_b: 0.0,
            sigma_c: 1.0e7,
            sigma_d: 0.0,
            pivot_frequency_hz: 1.0e6,
        });
        t
    }

    /// Register a new material. The id must be the next free slot or an
    /// existing slot; assignment writes the entry in place.
    pub fn register(&mut self, m: Material) {
        let idx = m.id.0 as usize;
        if idx >= self.entries.len() {
            self.entries.resize(idx + 1, AIR_PLACEHOLDER);
        }
        self.entries[idx] = m;
    }

    /// Look up by id. Out of range ids fall back to air, which is the safe
    /// default for unconfigured blocks during early world load.
    pub fn get(&self, id: MaterialId) -> &Material {
        self.entries.get(id.0 as usize).unwrap_or(&AIR_PLACEHOLDER)
    }

    /// Number of registered slots, including unfilled placeholders.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// True if no slots are registered.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Borrow the raw entry slice for GPU upload. Entries are indexed by
    /// material id; unregistered slots hold the air placeholder.
    pub fn entries(&self) -> &[Material] {
        &self.entries
    }
}

/// Placeholder used when an id falls outside the table or a slot has not
/// been registered yet. Always behaves like vacuum.
const AIR_PLACEHOLDER: Material = Material {
    id: MaterialId(0),
    name: "air-placeholder",
    eps_a: 1.0,
    eps_b: 0.0,
    sigma_c: 0.0,
    sigma_d: 0.0,
    pivot_frequency_hz: 1.0e6,
};