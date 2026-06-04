//! Radio wave propagation.
//!
//! Given a transmitter and a receiver pinned to world coordinates,
//! this module decides how much of the transmitter power survives
//! the trip, plus the Doppler shift and one way delay. The result
//! is a `PathLoss` that the spectrum mixer applies to
//! every Iq sample contributed by the emission.
//!
//! Layering
//! --------
//!
//! Each physical effect lives in its own submodule and is callable
//! standalone, so tests cover them in isolation. `solver` composes
//! them into the public `PropagationSolver` facade. Order of
//! application in the solver:
//!
//!   1. Friis free space loss for the straight line distance.
//!   2. Voxel raycast absorption along the line of sight.
//!   3. Bullington knife edge diffraction over the terrain profile.
//!   4. Ionospheric skywave correction when the line of sight path
//!      is closed and the frequency is below MUF.
//!   5. Rayleigh multipath fading multiplier.
//!   6. Doppler shift from relative radial velocity.
//!
//! Step 4 may turn a closed path back on at long range; step 5 may
//! turn a marginal path off temporarily. The chain is monotone in
//! none of these inputs except distance, which is exactly the
//! physics we want.

pub mod friis;
pub mod material;
pub mod voxel;
pub mod knife_edge;
pub mod curvature;
pub mod ionosphere;
pub mod fading;
pub mod doppler;
pub mod solver;

pub use doppler::doppler_shift_hz;
pub use fading::RayleighFading;
pub use friis::{free_space_loss_db, free_space_loss_linear, wavelength_m, SPEED_OF_LIGHT_M_S};
pub use ionosphere::{IonosphereLut, IonosphereSample, SolarActivity};
pub use knife_edge::{
    bullington_equivalent_edge, deygout_loss_db, diffraction_loss_db, fresnel_parameter,
    knife_edge_loss_db, BullingtonGeometry,
};
pub use curvature::{
    curvature_loss_db, link_radio_horizon_m, radio_horizon_m, MEAN_EARTH_RADIUS_M,
    STANDARD_K_FACTOR,
};
pub use material::{Material, MaterialId, MaterialTable};
pub use solver::{PropagationInputs, PropagationSolver, SolverConfig};
pub use voxel::{
    raycast_absorption_db, traverse, ChunkedVoxelGrid, DenseVoxelGrid, RaycastHit, VoxelGrid,
};
