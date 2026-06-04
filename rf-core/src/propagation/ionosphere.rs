//! Ionospheric skywave model.
//!
//! The shape of the model is intentionally coarse: a 24 entry lookup
//! table of F2 critical frequency over a day, parameterised by a
//! solar activity index. The MUF for a given hop is derived from the
//! secant law:
//!
//!     MUF = foF2 / cos(theta_i)
//!
//! where theta_i is the angle of incidence at the reflection point.
//! For a single hop over distance D with F2 layer height h_F2:
//!
//!     tan(theta_i) = (D / 2) / h_F2
//!     cos(theta_i) = h_F2 / sqrt(h_F2^2 + (D / 2)^2)
//!
//! Frequencies above MUF give an infinite ionospheric loss (signal
//! escapes to space). Frequencies below MUF pay a D-layer absorption
//! penalty that follows the inverse-square frequency law and the
//! solar zenith angle, multiplied by the hop count; the engine caps
//! the hop count at four. See `evaluate` for the absorption model.
//!
//! Numerical values for the foF2 table come from the standard
//! diurnal pattern in mid latitude solar minimum and maximum
//! summaries. Real space weather forecasts are far more nuanced,
//! but the table is enough to make night and day, summer and winter,
//! sound different on shortwave bands.

use core::f32::consts::PI;

/// Electron gyrofrequency term in the D-layer absorption law, in MHz.
/// Non-deviative absorption scales as 1/(f + fL)^2 where fL is the
/// longitudinal component of the gyrofrequency, near 1.4 MHz at mid
/// latitudes. Including it keeps the absorption finite as the
/// operating frequency falls toward the low end of HF instead of
/// running away the way a bare 1/f^2 term would.
const D_LAYER_GYRO_MHZ: f32 = 1.4;

/// Daytime D-layer absorption coefficient. Calibrated so that at the
/// solar zenith and 7 MHz a single hop loses about 14 dB, which puts
/// the lower HF bands firmly closed in the middle of the day and the
/// higher HF bands comfortably open, matching real band behaviour.
const D_LAYER_DAY_COEFF: f32 = 917.0;

/// Residual nighttime absorption coefficient. The D-layer all but
/// vanishes after dark, so this small floor stands in for the residual
/// E-region and lower-F absorption that keeps even a night path from
/// being completely lossless.
const D_LAYER_NIGHT_COEFF: f32 = 106.0;

/// Chapman exponent for the diurnal variation of D-layer ionization.
/// The layer's electron density tracks the cosine of the solar zenith
/// angle raised to a power near 0.75, and the absorption follows the
/// same law because it is proportional to that density.
const D_LAYER_CHAPMAN_EXPONENT: f32 = 0.75;

/// Solar activity bucket. The internal LUT is selected from this.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum SolarActivity {
    /// Solar minimum, R12 below 30.
    Low,
    /// Average, R12 around 70.
    Medium,
    /// Solar maximum, R12 above 130.
    High,
}

/// Snapshot of ionospheric conditions for one path query.
#[derive(Copy, Clone, Debug)]
pub struct IonosphereSample {
    /// F2 critical frequency, in hertz.
    pub fo_f2_hz: f64,
    /// Maximum usable frequency for the path, in hertz.
    pub muf_hz: f64,
    /// Hop count needed at the queried frequency (0 if the path is
    /// inside line of sight range and skywave is not used).
    pub hops: u8,
    /// Ionospheric absorption in decibels, applied on top of free
    /// space loss for the equivalent virtual path.
    pub absorption_db: f32,
    /// True when the path crosses the daylight boundary (D layer
    /// absorption rises sharply in that case).
    pub is_daytime: bool,
}

/// Lookup table for foF2 over a 24 hour day, selectable by solar
/// activity. Hour 0 is local midnight.
#[derive(Clone, Debug)]
pub struct IonosphereLut {
    activity: SolarActivity,
    f2_height_m: f32,
    table_hz: [f64; 24],
}

impl IonosphereLut {
    /// Default table for the given activity bucket. Internal values
    /// are typical mid latitude observations.
    pub fn for_activity(activity: SolarActivity) -> Self {
        let table_hz = match activity {
            // Low solar activity: foF2 swings roughly 2.5 to 5 MHz.
            SolarActivity::Low => [
                3.0e6, 2.8e6, 2.6e6, 2.5e6, 2.5e6, 2.7e6, 3.2e6, 4.0e6,
                4.5e6, 4.8e6, 5.0e6, 5.0e6, 5.0e6, 4.9e6, 4.7e6, 4.4e6,
                4.0e6, 3.7e6, 3.4e6, 3.2e6, 3.1e6, 3.1e6, 3.0e6, 3.0e6,
            ],
            // Medium activity: 3 to 8 MHz.
            SolarActivity::Medium => [
                4.5e6, 4.2e6, 4.0e6, 3.8e6, 3.7e6, 4.0e6, 5.0e6, 6.5e6,
                7.5e6, 8.0e6, 8.2e6, 8.2e6, 8.0e6, 7.8e6, 7.5e6, 7.0e6,
                6.5e6, 6.0e6, 5.5e6, 5.2e6, 5.0e6, 4.9e6, 4.7e6, 4.6e6,
            ],
            // High activity: 5 to 13 MHz.
            SolarActivity::High => [
                7.0e6, 6.5e6, 6.0e6, 5.8e6, 5.7e6, 6.2e6, 8.0e6, 10.5e6,
                12.0e6, 12.8e6, 13.0e6, 13.0e6, 12.8e6, 12.5e6, 12.0e6, 11.0e6,
                10.0e6, 9.0e6, 8.2e6, 7.7e6, 7.4e6, 7.2e6, 7.1e6, 7.0e6,
            ],
        };
        Self {
            activity,
            f2_height_m: 300_000.0,
            table_hz,
        }
    }

    /// Linear interpolation of foF2 by hour of the day. Wraps across
    /// midnight, so hour 23.5 blends entries 23 and 0.
    pub fn fo_f2_at_hour(&self, hour: f32) -> f64 {
        let normalized = hour.rem_euclid(24.0);
        let lo = normalized.floor() as usize % 24;
        let hi = (lo + 1) % 24;
        let t = (normalized - lo as f32) as f64;
        self.table_hz[lo] * (1.0 - t) + self.table_hz[hi] * t
    }

    /// Activity bucket the table was built for.
    pub fn activity(&self) -> SolarActivity {
        self.activity
    }

    /// Reflection height in metres.
    pub fn f2_height_m(&self) -> f32 {
        self.f2_height_m
    }

    pub fn evaluate(
        &self,
        frequency_hz: f64,
        ground_distance_m: f32,
        local_hour: f32,
    ) -> IonosphereSample {
        let fo_f2 = self.fo_f2_at_hour(local_hour);

        // Solar zenith angle proxy. The hour angle is zero at local
        // noon and swings to plus or minus pi at midnight; its cosine
        // stands in for cos(chi), peaking at one when the sun is
        // overhead at noon, crossing zero at the 06:00 and 18:00
        // terminators, and going negative through the night. This is
        // the latitude-free idealisation of a site where the sun
        // transits the zenith at noon, which is all the engine needs to
        // make dawn and dusk behave like a smooth grey line rather than
        // a hard switch.
        let hour_angle = (local_hour.rem_euclid(24.0) - 12.0) * PI / 12.0;
        let cos_zenith = hour_angle.cos();
        let is_day = cos_zenith > 0.0;

        let single_hop_max = 4_000_000.0_f32;
        let hops = ((ground_distance_m / single_hop_max).ceil() as i32).clamp(1, 4) as u8;
        let hop_length = ground_distance_m / hops as f32;

        let h = self.f2_height_m;
        let half = hop_length * 0.5;
        let cos_theta = h / (h * h + half * half).sqrt();
        let muf = if cos_theta > 0.0 { fo_f2 / cos_theta as f64 } else { f64::INFINITY };

        if frequency_hz > muf {
            return IonosphereSample {
                fo_f2_hz: fo_f2,
                muf_hz: muf,
                hops,
                absorption_db: f32::INFINITY,
                is_daytime: is_day,
            };
        }

        // Non-deviative D-layer absorption.
        //
        // Two physical dependencies replace the earlier binary
        // day/night step and the mild (1 + foF2/f) term, both of which
        // got the frequency behaviour wrong.
        //
        // Frequency: the absorption follows the inverse-square law
        // 1/(f + fL)^2, so doubling the operating frequency drops the
        // loss by roughly a factor of four. This is the single most
        // important band-selection rule on HF (work a higher band in
        // daylight to escape the D-layer), which my old
        // (1 + foF2/f) form barely expressed.
        //
        // Time of day: the daytime term scales with the solar zenith
        // angle through cos(chi)^0.75 (the Chapman law for the layer's
        // ionization), so absorption rises smoothly to a noon peak and
        // falls away through dusk instead of snapping between 6 dB and
        // 1 dB at the 06:00 and 18:00 boundaries. A small frequency
        // dependent night floor remains for the residual E-region.
        let day_factor = cos_zenith.max(0.0).powf(D_LAYER_CHAPMAN_EXPONENT);
        let f_mhz = (frequency_hz / 1.0e6) as f32;
        let denom = (f_mhz + D_LAYER_GYRO_MHZ) * (f_mhz + D_LAYER_GYRO_MHZ);
        let absorption_one_hop =
            (D_LAYER_DAY_COEFF * day_factor + D_LAYER_NIGHT_COEFF) / denom;
        let total_absorption = absorption_one_hop * hops as f32;

        IonosphereSample {
            fo_f2_hz: fo_f2,
            muf_hz: muf,
            hops,
            absorption_db: total_absorption,
            is_daytime: is_day,
        }
    }

    /// Virtual path length, in metres, for a given ground distance.
    /// Accounts for the climb to and descent from each F2 reflection
    /// point. The solver feeds this back into the Friis loss.
    pub fn virtual_path_length_m(&self, ground_distance_m: f32, hops: u8) -> f32 {
        if hops == 0 {
            return ground_distance_m;
        }
        let hop_length = ground_distance_m / hops as f32;
        let half = hop_length * 0.5;
        let h = self.f2_height_m;
        let virtual_one_hop = 2.0 * (h * h + half * half).sqrt();
        virtual_one_hop * hops as f32
    }
}

/// Convert a world tick count to an approximate local hour of the
/// day, given the Minecraft day length. The engine takes the
/// caller's word that ticks 0 corresponds to local 06:00, which
/// matches the game's noon at tick 6000.
#[inline]
pub fn world_tick_to_hour(tick: u64, ticks_per_day: u32) -> f32 {
    let _ = PI;
    let phase = (tick as f64 / ticks_per_day as f64).rem_euclid(1.0);
    let hour = phase * 24.0 + 6.0;
    (hour as f32).rem_euclid(24.0)
}