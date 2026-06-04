//! Earth curvature and the radio horizon.
//!
//! The flat voxel world has no horizon: a clear line of sight runs to
//! infinity, so a ground wave never dies of distance alone, only of
//! obstruction or spreading loss. Real terrestrial radio is bounded
//! first by the curve of the Earth dropping away beneath the path. This
//! module restores that bound analytically, independent of the voxel
//! grid, so it composes with the Fresnel aperture obstruction term
//! rather than replacing it: an obstacle blocks the path through the
//! voxel raycast, the Earth's curve blocks it through the model here,
//! and the two add.
//!
//! Effective Earth radius
//!
//! The atmosphere bends radio waves slightly downward, modelled by
//! straightening the rays and enlarging the Earth radius by the standard
//! k factor of 4/3. The radio horizon of one antenna of height h is then
//! sqrt(2 * k * Re * h); for the standard factor this reduces to the
//! familiar 4.12 * sqrt(h) kilometres for h in metres. A link between two
//! antennas reaches the sum of their two horizons before the bulge
//! between them rises into the path.
//!
//! Loss model
//!
//! The loss keys off the radio horizon directly, which is the physically
//! meaningful divide. A single knife edge over the bulge is wrong at both
//! ends of the range: within the horizon it reports several dB because
//! the line of sight clears the small bulge but not by a comfortable
//! fraction of the first Fresnel zone, so the knife edge reads the path
//! as nearly grazing; far beyond the horizon it reports only a few dB
//! because one sharp edge badly underestimates the diffraction loss of
//! the rounded Earth, which falls off far faster in the deep shadow.
//! Keying off the horizon avoids both:
//!
//!   * Inside the horizon, out to HORIZON_ONSET_FRACTION of it, the path
//!     is line of sight and the curvature loss is zero; free space loss
//!     and the Fresnel aperture term carry the budget there.
//!   * Over the last stretch up to the horizon the loss ramps smoothly
//!     from zero to GRAZING_LOSS_DB, the canonical knife edge grazing
//!     value, so there is no hard step at the horizon.
//!   * Beyond the horizon the loss is GRAZING_LOSS_DB plus a deep shadow
//!     term that grows linearly with the trans horizon distance. The per
//!     kilometre rate scales as f^(1/3) * ae^(-2/3), the frequency and
//!     effective radius dependence of the ITU-R spherical earth
//!     diffraction asymptote, so a higher band and a smaller world both
//!     steepen the fall.
//!
//! This is an engineering approximation of the full ITU-R P.526
//! spherical earth diffraction. The full method, with its F(X) distance
//! function and G(Y) height gain, is both heavier and, for the very low
//! antennas a ground level radio presents, harsh enough to drop tens of
//! dB right at the horizon, a near cliff that reads badly in game. The
//! linear deep shadow slope keeps the correct qualitative behaviour, a
//! clear path within the horizon and a steep climbing loss past it, with
//! one tunable constant.
//!
//! Compact worlds
//!
//! The real Earth radius gives a horizon near twelve kilometres for a
//! ground level antenna, which is realistic but lets long links survive
//! on tall towers. A smaller earth_radius_m pulls the horizon in and,
//! through the ae^(-2/3) term, steepens the beyond horizon slope, so a
//! server can compress the world to game scale without touching any of
//! the physics here.

/// Mean Earth radius in metres.
pub const MEAN_EARTH_RADIUS_M: f32 = 6_371_000.0;

/// Standard atmospheric k factor for the effective Earth radius.
pub const STANDARD_K_FACTOR: f32 = 4.0 / 3.0;

/// Loss in dB at the radio horizon itself, where the direct ray just
/// grazes the bulge. Matches the knife edge grazing value so the model
/// agrees with the diffraction model used elsewhere in the solver at the
/// one point they both describe.
const GRAZING_LOSS_DB: f32 = 6.0;

/// Fraction of the link horizon at which the loss begins to ramp up from
/// zero. Below this the path is comfortably line of sight; between this
/// and the horizon the loss climbs to the grazing value.
const HORIZON_ONSET_FRACTION: f32 = 0.8;

/// Deep shadow slope constant. Multiplied by f_mhz^(1/3) * ae_km^(-2/3)
/// it gives the beyond horizon attenuation rate in dB per kilometre. The
/// value is tuned so that, at the real Earth radius and VHF, a path a
/// couple of horizon lengths into the shadow loses tens of dB, which
/// closes ground level long links while leaving raised antennas and HF
/// skywave as the ways to reach far.
const DEEP_SHADOW_K: f32 = 60.0;

/// Radio horizon distance of a single antenna, in metres. The height is
/// measured above the local ground reference; a height at or below zero
/// yields a zero horizon. With the standard radius and k factor this is
/// the textbook 4.12 * sqrt(h) kilometres.
pub fn radio_horizon_m(height_m: f32, earth_radius_m: f32, k_factor: f32) -> f32 {
    let h = height_m.max(0.0);
    let r_eff = earth_radius_m.max(1.0) * k_factor.max(0.1);
    (2.0 * r_eff * h).sqrt()
}

/// Maximum line of sight distance of a two antenna link, the sum of the
/// two individual radio horizons. Past this ground distance the bulge
/// rises into the path and curvature_loss_db starts to bite.
pub fn link_radio_horizon_m(
    h1_m: f32,
    h2_m: f32,
    earth_radius_m: f32,
    k_factor: f32,
) -> f32 {
    radio_horizon_m(h1_m, earth_radius_m, k_factor)
        + radio_horizon_m(h2_m, earth_radius_m, k_factor)
}

/// Excess loss from Earth curvature for a ground wave path, in decibels.
///
/// `h1_m` and `h2_m` are the antenna heights above the ground reference,
/// `ground_distance_m` is the horizontal separation, and the radius and
/// k factor select the effective Earth. Returns zero for a path
/// comfortably within the horizon and a rising loss past it; see the
/// module docs for the three regions.
pub fn curvature_loss_db(
    h1_m: f32,
    h2_m: f32,
    ground_distance_m: f32,
    frequency_hz: f64,
    earth_radius_m: f32,
    k_factor: f32,
) -> f32 {
    let d = ground_distance_m;
    if !(d > 2.0) {
        return 0.0;
    }
    let r_eff = earth_radius_m.max(1.0) * k_factor.max(0.1);
    let horizon = (2.0 * r_eff * h1_m.max(0.0)).sqrt()
        + (2.0 * r_eff * h2_m.max(0.0)).sqrt();
    if horizon <= 0.0 {
        return 0.0;
    }

    let onset = HORIZON_ONSET_FRACTION * horizon;

    if d <= onset {
        // Line of sight with comfortable clearance: free space loss and
        // the Fresnel aperture term own this region.
        return 0.0;
    }

    if d <= horizon {
        // Approaching the horizon: ramp from zero at the onset to the
        // grazing value at the horizon so there is no hard step.
        let t = (d - onset) / (horizon - onset);
        return GRAZING_LOSS_DB * t;
    }

    // Beyond the horizon: grazing loss plus the deep shadow slope.
    let beyond_km = (d - horizon) / 1000.0;
    let ae_km = r_eff / 1000.0;
    let f_mhz = (frequency_hz / 1.0e6) as f32;
    let slope = DEEP_SHADOW_K * f_mhz.powf(1.0 / 3.0) * ae_km.powf(-2.0 / 3.0);
    GRAZING_LOSS_DB + slope * beyond_km
}