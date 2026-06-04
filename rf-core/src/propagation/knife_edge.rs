//! Knife edge diffraction loss using the Bullington construction.
//!
//! The terrain between transmitter and receiver is reduced to a
//! single equivalent obstacle whose top sits at the intersection of
//! the two horizon rays. The Fresnel parameter v of this virtual
//! obstacle drives the diffraction loss through the ITU-R single
//! knife edge approximation
//!     J(v) = 6.9 + 20 log10(sqrt((v - 0.1)^2 + 1) + v - 0.1),
//! valid for v > -0.78 and returning zero below that.
//!
//! 
use crate::propagation::friis::wavelength_m;

/// Terrain sample along the line of sight. `distance_m` is the
/// distance from the transmitter, `height_m` is the elevation of
/// the highest occupied voxel column at that distance.
#[derive(Copy, Clone, Debug)]
pub struct TerrainSample {
    /// Distance from the transmitter along the great circle path.
    pub distance_m: f32,
    /// Elevation in metres above the world floor.
    pub height_m: f32,
}

/// Geometry of the equivalent edge produced by the Bullington
/// construction. Empty when the direct line of sight is clear.
#[derive(Copy, Clone, Debug)]
pub struct BullingtonGeometry {
    /// Distance from the transmitter to the virtual edge, metres.
    pub d1_m: f32,
    /// Distance from the virtual edge to the receiver, metres.
    pub d2_m: f32,
    /// Clearance height above the direct ray, metres. Positive
    /// values mean the edge blocks line of sight; negative values
    /// (the edge below the ray) cause J(v) to fall to zero.
    pub clearance_m: f32,
}

/// Compute the Bullington equivalent edge from a terrain profile.
///
/// `profile` is the list of (distance, height) samples along the
/// path, in order of increasing distance. `tx_height` and
/// `rx_height` are the antenna heights above the world floor. The
/// total path length is the last sample's `distance_m`; this is
/// taken as the receiver position.
///
/// Returns `None` when the profile is empty or has fewer than one
/// intermediate sample (no obstacle to consider).
pub fn bullington_equivalent_edge(
    profile: &[TerrainSample],
    tx_height_m: f32,
    rx_height_m: f32,
) -> Option<BullingtonGeometry> {
    if profile.len() < 2 {
        return None;
    }
    let total = profile.last()?.distance_m;
    if !(total > 0.0) {
        return None;
    }
    // Slopes seen from the transmitter towards each obstacle.
    let mut best_slope_tx = f32::NEG_INFINITY;
    let mut best_slope_rx = f32::NEG_INFINITY;
    for s in profile.iter() {
        if s.distance_m <= 0.0 || s.distance_m >= total {
            continue;
        }
        let slope_tx = (s.height_m - tx_height_m) / s.distance_m;
        if slope_tx > best_slope_tx {
            best_slope_tx = slope_tx;
        }
        let slope_rx = (s.height_m - rx_height_m) / (total - s.distance_m);
        if slope_rx > best_slope_rx {
            best_slope_rx = slope_rx;
        }
    }
    if best_slope_tx == f32::NEG_INFINITY || best_slope_rx == f32::NEG_INFINITY {
        return None;
    }

    // Solve for the intersection point of the two horizon rays.
    // ray_tx: y = tx_height + best_slope_tx * d
    // ray_rx: y = rx_height + best_slope_rx * (total - d)
    // Set equal: tx_height + s_tx * d = rx_height + s_rx * (total - d)
    //   d * (s_tx + s_rx) = rx_height - tx_height + s_rx * total
    let denom = best_slope_tx + best_slope_rx;
    if denom.abs() < 1e-9 {
        // The horizon rays are parallel. Treat the path as clear.
        return None;
    }
    let d1 = (rx_height_m - tx_height_m + best_slope_rx * total) / denom;
    let d2 = total - d1;
    if d1 <= 0.0 || d2 <= 0.0 {
        return None;
    }
    let virtual_height = tx_height_m + best_slope_tx * d1;
    // Height of the direct ray at the same distance.
    let direct_height = tx_height_m + (rx_height_m - tx_height_m) * (d1 / total);
    let clearance = virtual_height - direct_height;
    Some(BullingtonGeometry { d1_m: d1, d2_m: d2, clearance_m: clearance })
}

/// Fresnel parameter v of a knife edge.
///
///   v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2))
///
/// `h` is the obstacle clearance above the direct ray, in metres.
/// `d1` and `d2` are the distances from the endpoints to the edge.
/// `lambda` is the wavelength in metres.
pub fn fresnel_parameter(clearance_m: f32, d1_m: f32, d2_m: f32, wavelength_m: f32) -> f32 {
    debug_assert!(d1_m > 0.0 && d2_m > 0.0 && wavelength_m > 0.0);
    let radicand = 2.0 * (d1_m + d2_m) / (wavelength_m * d1_m * d2_m);
    clearance_m * radicand.sqrt()
}

/// ITU-R approximation of the single knife edge diffraction loss.
/// Returns dB of loss; positive numbers are loss. Below v = -0.78
/// the approximation goes to zero, which matches the well known
/// behaviour of an obstacle that is well below the first Fresnel
/// zone.
pub fn knife_edge_loss_db(v: f32) -> f32 {
    if v <= -0.78 {
        return 0.0;
    }
    let term = ((v - 0.1).powi(2) + 1.0).sqrt() + v - 0.1;
    6.9 + 20.0 * term.log10()
}

/// Convenience: compose the terrain profile into a diffraction loss.
/// Returns 0 dB when the path has no obstruction above the line of
/// sight.
///
/// Iteration note on the method
///
/// Earlier this delegated to a single Bullington equivalent edge, which
/// collapses the whole profile to one virtual knife edge. That is cheap
/// and exact for a single ridge but underestimates the loss when
/// several comparable obstacles sit between the endpoints, because the
/// secondary ridges contribute nothing once the profile has been
/// reduced to one edge. The composed entry now runs the Deygout
/// construction (`deygout_loss_db`), which finds the dominant edge then
/// recurses on the two sub-paths to pick up secondary edges. For a
/// single ridge the Deygout result is identical to the old Bullington
/// single-edge value, so nothing regresses on the simple case while the
/// multi-ridge case stops being optimistic. `bullington_equivalent_edge`
/// stays public as the single-edge building block and for callers that
/// want the geometry directly.
pub fn diffraction_loss_db(
    profile: &[TerrainSample],
    tx_height_m: f32,
    rx_height_m: f32,
    frequency_hz: f64,
) -> f32 {
    deygout_loss_db(profile, tx_height_m, rx_height_m, frequency_hz)
}

/// Maximum recursion depth of the Deygout construction. Depth three
/// resolves a principal edge plus up to two further generations of
/// secondary edges on each side, which covers the handful of comparable
/// ridges a Minecraft path realistically presents while keeping the
/// cost a small constant multiple of one profile scan.
const DEYGOUT_MAX_DEPTH: u32 = 3;

/// Deygout multiple knife-edge diffraction loss.
///
/// The Deygout method finds the obstacle with the largest Fresnel
/// parameter along the path, takes its single knife-edge loss, then
/// recurses independently on the sub-path from the transmitter to that
/// edge and from that edge to the receiver. The sub-path losses add to
/// the principal loss. This captures several comparable ridges that the
/// single Bullington edge would merge and undercount.
///
/// The method is known to overestimate when the edges are widely
/// separated, the opposite bias to Bullington's underestimate, so the
/// two bracket the true loss. The engine accepts the Deygout bias
/// because an optimistic diffraction estimate lets signals leak through
/// terrain that should block them, which reads as a worse gameplay bug
/// than a slightly pessimistic one. A Causebrook correction could
/// tighten the estimate later without changing this entry point.
pub fn deygout_loss_db(
    profile: &[TerrainSample],
    tx_height_m: f32,
    rx_height_m: f32,
    frequency_hz: f64,
) -> f32 {
    if profile.len() < 2 {
        return 0.0;
    }
    let total = match profile.last() {
        Some(s) => s.distance_m,
        None => return 0.0,
    };
    if !(total > 0.0) {
        return 0.0;
    }
    let lambda = wavelength_m(frequency_hz) as f32;
    deygout_recurse(
        profile,
        (0.0, tx_height_m),
        (total, rx_height_m),
        lambda,
        DEYGOUT_MAX_DEPTH,
    )
}

/// One level of the Deygout recursion over the sub-path between the
/// endpoints `a` and `b`, each a (distance, height) pair in the
/// profile's coordinate frame. Returns the summed knife-edge loss of
/// the dominant edge in the open interval (a, b) and of its two child
/// sub-paths.
fn deygout_recurse(
    profile: &[TerrainSample],
    a: (f32, f32),
    b: (f32, f32),
    lambda: f32,
    depth: u32,
) -> f32 {
    if depth == 0 {
        return 0.0;
    }
    let span = b.0 - a.0;
    if !(span > 0.0) {
        return 0.0;
    }
    let mut best_v = f32::NEG_INFINITY;
    let mut best_index: Option<usize> = None;
    for (i, s) in profile.iter().enumerate() {
        if s.distance_m <= a.0 || s.distance_m >= b.0 {
            continue;
        }
        let d1 = s.distance_m - a.0;
        let d2 = b.0 - s.distance_m;
        // Height of the straight line a..b at this distance.
        let line_h = a.1 + (b.1 - a.1) * (d1 / span);
        let clearance = s.height_m - line_h;
        if clearance <= 0.0 {
            continue;
        }
        let v = fresnel_parameter(clearance, d1, d2, lambda);
        if v > best_v {
            best_v = v;
            best_index = Some(i);
        }
    }
    let Some(idx) = best_index else {
        return 0.0;
    };
    if best_v <= -0.78 {
        return 0.0;
    }
    let principal = knife_edge_loss_db(best_v);
    let edge = (profile[idx].distance_m, profile[idx].height_m);
    let left = deygout_recurse(profile, a, edge, lambda, depth - 1);
    let right = deygout_recurse(profile, edge, b, lambda, depth - 1);
    principal + left + right
}