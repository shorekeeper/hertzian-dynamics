//! Material database for voxel absorption.
//!
//! Each material has a base attenuation in dB per metre at a
//! reference frequency. Real materials have a complex dielectric
//! constant that gives a frequency dependent loss; for the engine
//! we use a single multiplier exponent that captures the trend
//! without a full dispersion table. The default scaling is
//! linear in frequency above 30 MHz and flat below, which is a
//! reasonable shortcut for the broad strokes the simulation needs.

/// Stable identifier of a material in the table. The JNI layer
/// will hand these back to Java as `int` indices; never reuse a
/// value across schema versions.
#[repr(transparent)]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct MaterialId(pub u16);

impl MaterialId {
    /// The empty voxel. Air does not absorb at the frequencies the
    /// engine covers, so this is always the zero attenuation entry.
    pub const AIR: Self = Self(0);
}

/// Static material description.
#[derive(Copy, Clone, Debug)]
pub struct Material {
    /// Identifier; equal to the index of this entry in the table.
    pub id: MaterialId,
    /// Stable display name for diagnostics.
    pub name: &'static str,
    /// Attenuation in dB per metre at `reference_frequency_hz`.
    pub atten_db_per_m_at_ref: f32,
    /// Reference frequency at which the attenuation was measured.
    pub reference_frequency_hz: f32,
    /// Exponent of the linear scaling above `pivot_frequency_hz`.
    /// The actual attenuation is
    ///   atten = base * max(1, f / pivot)^scaling_exponent.
    /// 1.0 is the conventional dispersion of lossy dielectrics in
    /// the HF to VHF range; 0.0 disables scaling.
    pub scaling_exponent: f32,
    /// Pivot frequency below which the attenuation stays flat.
    pub pivot_frequency_hz: f32,
}

impl Material {
    /// Attenuation in decibels per metre at an arbitrary frequency.
    ///
    /// The model is the single-slope power law atten = ref *
    /// (f / ref)^exponent, the standard band approximation also used by
    /// ITU-R P.2040 for building materials. The exponent encodes the
    /// dominant loss regime of the material rather than being a free
    /// fitting knob: a good conductor loses energy through the skin
    /// effect and scales as the square root of frequency (exponent
    /// 0.5), a dielectric with a roughly constant loss tangent scales
    /// about linearly (exponent 1.0), and vegetation sits near the
    /// square-root end as well. A single slope cannot capture the full
    /// conductor-to-dielectric transition a material crosses over many
    /// decades, so the exponent is chosen for the dominant regime over
    /// the HF-to-VHF band the engine actually uses. The per-metre
    /// magnitudes are calibrated for 1 m solid voxels and are gameplay
    /// figures, not laboratory measurements.
    ///
    /// Scaling rule, applied in three regions:
    ///
    ///
    /// * `frequency_hz >= reference_frequency_hz`
    ///     Above the reference the attenuation grows as a power
    ///     law:
    ///         atten = ref * (f / ref)^scaling_exponent.
    ///
    /// * `pivot_frequency_hz <= frequency_hz < reference_frequency_hz`
    ///     Between the pivot and the reference the attenuation
    ///     stays flat at `atten_db_per_m_at_ref`. The simulation
    ///     does not pretend to know how a material behaves at
    ///     frequencies it was not characterised for, so the safe
    ///     default is "no extra penalty".
    ///
    ///  * `frequency_hz < pivot_frequency_hz`
    ///     Below the pivot the frequency is clamped to the pivot
    ///     and the previous rule applies. The clamp guards against
    ///     pathological inputs (zero or near zero frequencies) and
    ///     leaves the value flat at `atten_db_per_m_at_ref`.
    pub fn attenuation_db_per_m(&self, frequency_hz: f64) -> f32 {
        let f_eff = (frequency_hz as f32).max(self.pivot_frequency_hz);
        if f_eff <= self.reference_frequency_hz {
            return self.atten_db_per_m_at_ref;
        }
        let ratio = f_eff / self.reference_frequency_hz;
        self.atten_db_per_m_at_ref * ratio.powf(self.scaling_exponent)
    }
}

/// Lookup table indexed by MaterialId. Stored as a Vec for cheap
/// growth at startup, then frozen by the time the solver runs.
#[derive(Clone, Debug, Default)]
pub struct MaterialTable {
    entries: Vec<Material>,
}

impl MaterialTable {
    /// Empty table. The caller must register AIR before doing any
    /// useful work; `with_defaults` does this and adds a handful of
    /// stock materials.
    pub fn new() -> Self {
        Self { entries: Vec::new() }
    }

    pub fn with_defaults() -> Self {
        let mut t = Self::new();
        // Air. Reference values do not matter, attenuation stays 0.
        t.register(Material {
            id: MaterialId(0),
            name: "air",
            atten_db_per_m_at_ref: 0.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 0.0,
            pivot_frequency_hz: 100e6,
        });
        // Stone (dense composite, closer to reinforced concrete than
        // dry brick because Minecraft voxels are 1 m solid blocks).
        // Lossy dielectric: over the HF-VHF band a roughly constant loss
        // tangent gives attenuation close to linear in frequency, so the
        // exponent stays at 1.0.
        t.register(Material {
            id: MaterialId(1),
            name: "stone",
            atten_db_per_m_at_ref: 25.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 1.0,
            pivot_frequency_hz: 30e6,
        });
        // Water at voxel scale. One block of water is a small pond
        // cross-section, very lossy. Treated as conductivity-dominated
        // (sea-water-like): where ionic conductivity dwarfs the
        // displacement current the loss follows the skin effect, rising
        // as the square root of frequency, so the exponent is 0.5 rather
        // than the earlier linear-plus 1.2.
        t.register(Material {
            id: MaterialId(2),
            name: "water",
            atten_db_per_m_at_ref: 40.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 0.5,
            pivot_frequency_hz: 30e6,
        });
        // Wood. Dry low-loss dielectric: a roughly constant loss tangent
        // makes attenuation grow about linearly with frequency, so the
        // exponent is 1.0 rather than the earlier 0.8.
        t.register(Material {
            id: MaterialId(3),
            name: "wood",
            atten_db_per_m_at_ref: 2.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 1.0,
            pivot_frequency_hz: 30e6,
        });
        // Glass. Low-loss dielectric like wood; the near-constant loss
        // tangent gives a near-linear frequency dependence, so the
        // exponent is 1.0 rather than the earlier 0.6.
        t.register(Material {
            id: MaterialId(4),
            name: "glass",
            atten_db_per_m_at_ref: 3.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 1.0,
            pivot_frequency_hz: 30e6,
        });
        // Dirt and similar earth materials. Moist soil behaves as a
        // lossy dielectric with a near-linear frequency dependence over
        // the band, so the exponent stays at 1.0.
        t.register(Material {
            id: MaterialId(5),
            name: "dirt",
            atten_db_per_m_at_ref: 15.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 1.0,
            pivot_frequency_hz: 30e6,
        });
        // Leaves and foliage. Light scatterer. Vegetation attenuation
        // grows roughly as the square root of frequency in the standard
        // foliage models, so the exponent stays at 0.5.
        t.register(Material {
            id: MaterialId(6),
            name: "leaves",
            atten_db_per_m_at_ref: 1.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 0.5,
            pivot_frequency_hz: 30e6,
        });
        // Iron. At voxel scale this is a solid 1 m cube of ferrous
        // metal; shielding is effectively absolute once thickness
        // exceeds a few skin depths. As a good conductor its loss
        // follows the skin effect, rising as the square root of
        // frequency, so the exponent is 0.5.
        t.register(Material {
            id: MaterialId(7),
            name: "iron",
            atten_db_per_m_at_ref: 200.0,
            reference_frequency_hz: 100e6,
            scaling_exponent: 0.5,
            pivot_frequency_hz: 30e6,
        });
        t
    }

    /// Register a new material. The id must be the next free slot
    /// or an existing slot; assignment writes the entry in place.
    pub fn register(&mut self, m: Material) {
        let idx = m.id.0 as usize;
        if idx >= self.entries.len() {
            self.entries
                .resize(idx + 1, AIR_PLACEHOLDER);
        }
        self.entries[idx] = m;
    }

    /// Look up by id. Out of range ids fall back to air, which is
    /// the safe default for unconfigured blocks during early world
    /// load.
    pub fn get(&self, id: MaterialId) -> &Material {
        self.entries
            .get(id.0 as usize)
            .unwrap_or(&AIR_PLACEHOLDER)
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

/// Placeholder used when an id falls outside the table or a slot
/// has not been registered yet. Always behaves like air.
const AIR_PLACEHOLDER: Material = Material {
    id: MaterialId(0),
    name: "air-placeholder",
    atten_db_per_m_at_ref: 0.0,
    reference_frequency_hz: 100e6,
    scaling_exponent: 0.0,
    pivot_frequency_hz: 100e6,
};