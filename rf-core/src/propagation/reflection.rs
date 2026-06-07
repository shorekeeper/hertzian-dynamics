//! Ground reflection: the two-ray model and Fresnel reflection
//! coefficients.
//!
//! On a terrestrial path the receiver collects, besides the direct ray, a
//! ray that reflects off the ground between the endpoints. The two
//! contributions add as complex phasors, so depending on their phase
//! difference the sum can be larger than the direct ray alone
//! (constructive interference) or much smaller (a deep multipath null).
//! This is the dominant non line of sight mechanism on VHF and UHF, where
//! a 145 MHz wave has a two metre wavelength and the ground sits well
//! inside the first Fresnel zone.
//!
//! Two-ray geometry
//! ----------------
//!
//! Place the transmitter at height h_t and the receiver at height h_r
//! above a flat ground separated by horizontal distance d. The direct ray
//! has length
//!
//!     r1 = sqrt(d^2 + (h_t - h_r)^2)
//!
//! and the reflected ray, found by the image method (an image transmitter
//! at -h_t), has length
//!
//!     r2 = sqrt(d^2 + (h_t + h_r)^2).
//!
//! The reflected ray strikes the ground at grazing angle psi where
//!
//!     sin(psi) = (h_t + h_r) / r2,    cos(psi) = d / r2.
//!
//! Received power relative to free space
//! -------------------------------------
//!
//! Free space received power assumes the direct ray alone,
//!
//!     P_fs = P_t * G * (lambda / (4 pi r1))^2.
//!
//! The two-ray received power superposes the two phasors,
//!
//!     P_2ray = P_t * G * (lambda / 4 pi)^2
//!              * | e^{-j k r1} / r1 + Gamma * e^{-j k r2} / r2 |^2,
//!
//! with k = 2 pi / lambda the wavenumber and Gamma the complex ground
//! reflection coefficient. The ratio of the two, which is the correction
//! this module returns in decibels, is
//!
//!     P_2ray / P_fs = | 1 + Gamma * (r1/r2) * e^{-j k (r2 - r1)} |^2.
//!
//! Write Delta = r2 - r1 for the path difference. For d much larger than
//! the antenna heights this reduces to the familiar Delta ~ 2 h_t h_r / d.
//!
//! The d^4 regime
//! --------------
//!
//! At grazing incidence (small psi, large d) the reflection coefficient
//! tends to Gamma = -1, so the two rays nearly cancel. Expanding the
//! correction for small k*Delta with Gamma = -1 and r1/r2 ~ 1,
//!
//!     | 1 - e^{-j k Delta} |^2 ~ (k Delta)^2 = (4 pi h_t h_r / (lambda d))^2,
//!
//! which falls as 1/d^2 on top of the free space 1/d^2, giving the total
//! 1/d^4 power law. Adding the free space loss and this correction
//! algebraically yields the textbook result
//!
//!     PL = 40 log10(d) - 20 log10(h_t h_r)    (with unity antenna gains),
//!
//! a 40 dB per decade slope past the critical distance d_c = 4 h_t h_r /
//! lambda. Below d_c the correction oscillates between constructive peaks
//! near +6 dB (two equal rays in phase) and destructive nulls.
//!
//! Lossy ground saturates the null
//! -------------------------------
//!
//! The clean d^4 law needs |Gamma| = 1, which holds only for a perfect
//! reflector or at true grazing. Over real ground the wave loses energy
//! into the surface, so |Gamma| < 1 and the cancellation is incomplete:
//! as d grows the correction tends to (1 - |Gamma|)^2 rather than zero, so
//! the reflection induced loss saturates near 20 log10(1 / (1 - |Gamma|))
//! instead of growing without bound. This is physical and is why the
//! engine caps the null depth with a configurable floor.
//!
//! Fresnel reflection coefficient
//! ------------------------------
//!
//! Gamma depends on the grazing angle, the polarization and the complex
//! relative permittivity eta = epsilon_r - j epsilon'' of the ground (the
//! same quantity derived in propagation::material). With the substitution
//! root = sqrt(eta - cos^2 psi),
//!
//!     horizontal (TE):  Gamma_h = (sin psi - root) / (sin psi + root)
//!     vertical (TM):    Gamma_v = (eta sin psi - root) / (eta sin psi + root).
//!
//! At true grazing (psi -> 0) both tend to -1. Away from grazing the two
//! polarizations diverge sharply. Vertical polarization passes through the
//! pseudo-Brewster angle, near psi_B ~ asin(1 / sqrt(|eta|)), where
//! |Gamma_v| dips to a minimum and its phase swings through. Over a good
//! conductor the pseudo-Brewster angle is extremely small, so at ordinary
//! grazing angles a vertically polarized wave reflects with Gamma_v near
//! +1 (the two rays add) rather than -1, and the d^4 null does not form;
//! horizontal polarization over the same conductor keeps Gamma_h = -1 and
//! does form it. Capturing this polarization dependent behaviour is the
//! point of carrying the full complex Fresnel coefficient rather than a
//! fixed Gamma = -1.

/// Antenna polarization relative to the ground plane.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum Polarization {
    /// Electric field parallel to the ground (perpendicular to the plane
    /// of incidence). The transverse electric case.
    Horizontal,
    /// Electric field in the plane of incidence. The transverse magnetic
    /// case, the realistic default for a vertical whip or monopole.
    Vertical,
}

/// Minimal f64 complex number. A dedicated type keeps the crate free of a
/// complex number dependency while giving the Fresnel arithmetic the f64
/// headroom it needs: the principal branch square root and the division
/// both lose precision badly in f32 near the grazing and Brewster regions.
#[derive(Copy, Clone, Debug)]
struct Cplx {
    re: f64,
    im: f64,
}

impl Cplx {
    const fn new(re: f64, im: f64) -> Self {
        Self { re, im }
    }

    fn add(self, o: Cplx) -> Cplx {
        Cplx::new(self.re + o.re, self.im + o.im)
    }

    fn sub(self, o: Cplx) -> Cplx {
        Cplx::new(self.re - o.re, self.im - o.im)
    }

    fn mul(self, o: Cplx) -> Cplx {
        Cplx::new(self.re * o.re - self.im * o.im, self.re * o.im + self.im * o.re)
    }

    fn scale(self, k: f64) -> Cplx {
        Cplx::new(self.re * k, self.im * k)
    }

    fn div(self, o: Cplx) -> Cplx {
        let d = o.re * o.re + o.im * o.im;
        Cplx::new((self.re * o.re + self.im * o.im) / d, (self.im * o.re - self.re * o.im) / d)
    }

    fn abs(self) -> f64 {
        (self.re * self.re + self.im * self.im).sqrt()
    }

    fn abs_sq(self) -> f64 {
        self.re * self.re + self.im * self.im
    }

    /// Principal branch square root. For z = a + jb the result has
    /// non negative real part; the imaginary part takes the sign of b, so
    /// a permittivity with negative imaginary part (a lossy, decaying
    /// medium) yields a root with negative imaginary part, the physically
    /// correct branch for an inward decaying wave.
    fn sqrt(self) -> Cplx {
        let r = self.abs();
        let re = (0.5 * (r + self.re)).max(0.0).sqrt();
        let mut im = (0.5 * (r - self.re)).max(0.0).sqrt();
        if self.im < 0.0 {
            im = -im;
        }
        Cplx::new(re, im)
    }

    /// Multiply by e^{j phi}.
    fn rotate(self, phi: f64) -> Cplx {
        let (s, c) = phi.sin_cos();
        self.mul(Cplx::new(c, s))
    }

    fn phase(self) -> f64 {
        self.im.atan2(self.re)
    }
}

/// Complex Fresnel reflection coefficient for a wave incident on the
/// ground at grazing angle psi. `eta` is the complex relative
/// permittivity eta = epsilon_r - j epsilon''.
fn fresnel_gamma(eta: Cplx, sin_psi: f64, cos_psi: f64, pol: Polarization) -> Cplx {
    let root = eta.sub(Cplx::new(cos_psi * cos_psi, 0.0)).sqrt();
    match pol {
        Polarization::Horizontal => {
            let s = Cplx::new(sin_psi, 0.0);
            s.sub(root).div(s.add(root))
        }
        Polarization::Vertical => {
            let es = eta.scale(sin_psi);
            es.sub(root).div(es.add(root))
        }
    }
}

/// Fresnel reflection coefficient as magnitude and phase, in radians, for
/// the given ground permittivity, grazing angle and polarization. The
/// permittivity is passed as (epsilon_r, epsilon'') with epsilon'' the
/// non negative loss term sigma / (omega epsilon_0); the imaginary part of
/// eta is its negation. Exposed for diagnostics and for the polarization
/// work in later iterations.
pub fn fresnel_reflection(
    eps_r: f32,
    eps_imag: f32,
    grazing_rad: f32,
    pol: Polarization,
) -> (f32, f32) {
    let eta = Cplx::new(eps_r as f64, -(eps_imag as f64));
    let g = grazing_rad as f64;
    let gamma = fresnel_gamma(eta, g.sin(), g.cos(), pol);
    (gamma.abs() as f32, gamma.phase() as f32)
}

/// Two-ray ground reflection gain in decibels, relative to the free space
/// loss of the direct ray.
///
/// Returns 10 log10 of the correction factor described in the module
/// documentation. A positive value means the reflected ray adds to the
/// direct ray (up to +6 dB for two equal rays in phase); a negative value
/// means a partial null. The caller subtracts this gain from the ground
/// wave loss budget.
///
/// `eps_r` and `eps_imag` are the ground complex permittivity components,
/// `h_t` and `h_r` the antenna heights above the local ground, and
/// `ground_distance_m` the horizontal separation. `reflected_absorption_db`
/// is the absorption a building places on the reflected ray (zero for a
/// clear reflection); it attenuates the reflected phasor amplitude, so a
/// fully blocked reflected ray collapses the factor to unity and the gain
/// to zero, leaving only the direct ray. `null_floor_db` caps the depth of
/// a destructive null so the result never runs to negative infinity; a
/// real receiver never sees an infinitely deep null because of finite
/// bandwidth and surface roughness.
pub fn two_ray_gain_db(
    eps_r: f32,
    eps_imag: f32,
    h_t: f32,
    h_r: f32,
    ground_distance_m: f32,
    wavelength_m: f32,
    reflected_absorption_db: f32,
    pol: Polarization,
    null_floor_db: f32,
) -> f32 {
    let d = ground_distance_m as f64;
    let ht = h_t as f64;
    let hr = h_r as f64;
    let lambda = wavelength_m as f64;
    if d <= 0.0 || lambda <= 0.0 {
        return 0.0;
    }

    let r1 = (d * d + (ht - hr) * (ht - hr)).sqrt();
    let r2 = (d * d + (ht + hr) * (ht + hr)).sqrt();
    let sin_psi = (ht + hr) / r2;
    let cos_psi = d / r2;

    let eta = Cplx::new(eps_r as f64, -(eps_imag as f64));
    let gamma = fresnel_gamma(eta, sin_psi, cos_psi, pol);

    let k = std::f64::consts::TAU / lambda;
    let delta = r2 - r1;
    let atten = 10f64.powf(-(reflected_absorption_db as f64) / 20.0);

    // Reflected phasor: Gamma * (r1/r2) * e^{-j k Delta}, scaled by the
    // building attenuation of the reflected ray.
    let reflected = gamma.rotate(-k * delta).scale((r1 / r2) * atten);
    let sum = Cplx::new(1.0, 0.0).add(reflected);

    let mut factor = sum.abs_sq();
    let floor = 10f64.powf(-(null_floor_db as f64) / 10.0);
    if factor < floor {
        factor = floor;
    }
    (10.0 * factor.log10()) as f32
}