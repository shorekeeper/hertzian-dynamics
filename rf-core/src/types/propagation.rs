//! Path loss descriptors.
//!
//! `PathLoss` is the value the `PropagationSolver` returns for one (Tx,
//! Rx, frequency) triple. The interference mixer consumes it twice: once
//! to scale every Iq sample contributed by that transmitter, and once to
//! bias the noise floor estimator.
//!
//! All numeric fields are linear unless their name ends in `_db`. The
//! breakdown carries each component separately so the GUI S-meter and the
//! diagnostic dumps can present a useful split instead of one opaque
//! number.

/// Per-component contribution to the total path loss, in decibels.
/// Positive values are loss. Two fields can be negative: `fading_db` on a
/// constructive Rayleigh draw, and `reflection_db` when the ground
/// reflected ray adds constructively to the direct ray.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default, PartialEq)]
pub struct LossBreakdown {
    /// Friis free space loss for the straight line distance.
    pub free_space_db: f32,
    /// Obstruction loss from the first Fresnel zone, combining material
    /// penetration on the line of sight with partial blockage of the
    /// surrounding zone. Produced by the solver's Fresnel aperture model
    /// (see propagation::solver). The aperture bundle is clamped to the
    /// ground surface, so this does not double count the ground that the
    /// reflection term owns.
    pub absorption_db: f32,
    /// Earth curvature radio horizon loss for the ground wave. Zero on a
    /// skywave path. The field name is kept for layout stability; the
    /// standalone knife edge functions in propagation::knife_edge still
    /// compute a true diffraction term for terrain profile callers.
    pub diffraction_db: f32,
    /// Two-ray ground reflection contribution. Positive when the reflected
    /// ray partially cancels the direct ray (a multipath null), negative
    /// when it adds constructively (up to about -6 dB). Zero on a skywave
    /// path. See propagation::reflection for the model.
    pub reflection_db: f32,
    /// Ionospheric component. Positive means an extra penalty over the
    /// ground wave path; `f32::INFINITY` flags an unreachable skywave
    /// geometry (frequency above MUF).
    pub ionospheric_db: f32,
    /// Stochastic multipath fade, drawn from a Rayleigh distribution at
    /// solver time. Can be slightly negative on a constructive draw.
    pub fading_db: f32,
}

/// Result of one solver evaluation.
///
/// `linear` is the receive-to-transmit power ratio in linear units,
/// suitable for direct multiplication of Iq samples (apply `sqrt(linear)`
/// to amplitudes; the mixer prefers power and squares magnitudes anyway).
/// `db` is the same number expressed in decibels for human consumption.
#[repr(C)]
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct PathLoss {
    /// Linear receive-to-transmit power ratio. Includes antenna gains.
    /// Always in [0, finite]. Zero means the path is closed (either
    /// complete blockage or skywave gone).
    pub linear: f32,
    /// Same as `linear`, expressed as a positive loss in dB. A path that
    /// is closed reports `f32::INFINITY` here.
    pub db: f32,
    /// Component breakdown for diagnostics.
    pub components: LossBreakdown,
    /// Doppler shift in hertz, signed. Positive means the receiver sees a
    /// higher frequency than the transmitter emits.
    pub doppler_hz: f32,
    /// One way propagation delay in seconds. Used by the spectrum manager
    /// to schedule audio chunks correctly when transmitters are far away.
    pub delay_seconds: f32,
    /// RMS delay spread of the multipath channel, in seconds. Non zero
    /// only in the Multipath model, where several resolvable paths reach
    /// the receiver. A diagnostic of the channel's frequency selectivity;
    /// the narrowband mixer does not consume it because one voice channel
    /// is far narrower than the coherence bandwidth implied by these
    /// delays.
    pub rms_delay_spread_s: f32,
}

impl PathLoss {
    /// A path with no loss and no Doppler. Convenient default for tests
    /// that exercise a single component in isolation.
    pub const UNIT: Self = Self {
        linear: 1.0,
        db: 0.0,
        components: LossBreakdown {
            free_space_db: 0.0,
            absorption_db: 0.0,
            diffraction_db: 0.0,
            reflection_db: 0.0,
            ionospheric_db: 0.0,
            fading_db: 0.0,
        },
        doppler_hz: 0.0,
        delay_seconds: 0.0,
        rms_delay_spread_s: 0.0,
    };

    /// A fully closed path. The mixer treats this as if the transmitter
    /// were not in range at all.
    pub const CLOSED: Self = Self {
        linear: 0.0,
        db: f32::INFINITY,
        components: LossBreakdown {
            free_space_db: f32::INFINITY,
            absorption_db: 0.0,
            diffraction_db: 0.0,
            reflection_db: 0.0,
            ionospheric_db: 0.0,
            fading_db: 0.0,
        },
        doppler_hz: 0.0,
        delay_seconds: 0.0,
        rms_delay_spread_s: 0.0,
    };

    /// True if the path delivers no usable signal.
    pub fn is_closed(&self) -> bool {
        self.linear <= 0.0 || !self.db.is_finite()
    }
}

const _: () = {
    assert!(core::mem::size_of::<LossBreakdown>() == 24);
    assert!(core::mem::size_of::<PathLoss>() >= 44);
};