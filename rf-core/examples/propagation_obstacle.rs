//! Solve the link budget through three terrain configurations: clear
//! air, a stone ridge, and a dense concrete wall (modelled as iron).
//!
//! Run: cargo run --example propagation_obstacle
//!
//! What to look for (Fresnel aperture model):
//!   * Clear air loss is the Friis value at 200 m; the obstruction
//!     column (abs) reads near zero.
//!   * The stone ridge only covers part of the first Fresnel zone, so
//!     the signal diffracts around the open sides and the obstruction is
//!     a partial loss, not a closed path.
//!   * The iron wall spans the whole cross section, leaving no clear part
//!     of the zone, so the obstruction runs to the budget and the path
//!     closes (linear ratio collapses toward zero). The diff column is
//!     retained for layout and stays zero: the aperture model carries
//!     diffraction inside the abs term.

use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialId, MaterialTable, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};

fn main() {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);
    let mut fading = RayleighFading::new(0xC0FFEE, 0.0);

    let tx = [0.0, 5.0, 0.0];
    let rx = [200.0, 5.0, 0.0];

    let cases: &[(&str, fn() -> DenseVoxelGrid)] = &[
        ("clear", build_clear),
        ("ridge", build_stone_ridge),
        ("iron", build_iron_wall),
    ];

    for (label, builder) in cases {
        let grid = builder();
        let solver = PropagationSolver::new(&grid, &materials, &iono, SolverConfig::default());
        let inputs = PropagationInputs {
            tx_pos: tx,
            rx_pos: rx,
            tx_vel: [0.0; 3],
            rx_vel: [0.0; 3],
            frequency_hz: 100.0e6,
            tx_gain: 1.0,
            rx_gain: 1.0,
            local_hour: 12.0,
        };
        let pl = solver.solve(inputs, &mut fading);
        println!(
            "{label:<6}: total {:>7.1} dB | fs {:>5.1} abs {:>5.1} diff {:>5.1} iono {:>5.1} fade {:>+5.1} | linear {:.3e}",
            pl.db,
            pl.components.free_space_db,
            pl.components.absorption_db,
            pl.components.diffraction_db,
            pl.components.ionospheric_db,
            pl.components.fading_db,
            pl.linear
        );
    }
}

fn build_clear() -> DenseVoxelGrid {
    DenseVoxelGrid::new(1.0, [-10, -10, -10], [240, 50, 30])
}

fn build_stone_ridge() -> DenseVoxelGrid {
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -10], [240, 50, 30]);
    for x in 90..110 {
        for y in 0..30 {
            for z in -5..5 {
                g.set(x, y, z, MaterialId(1));
            }
        }
    }
    g
}

fn build_iron_wall() -> DenseVoxelGrid {
    // Span the full grid cross section so no part of the Fresnel zone is
    // left clear; only then does the aperture model close the path.
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -10], [240, 50, 30]);
    for x in 99..101 {
        for y in -10..40 {
            for z in -10..20 {
                g.set(x, y, z, MaterialId(7));
            }
        }
    }
    g
}