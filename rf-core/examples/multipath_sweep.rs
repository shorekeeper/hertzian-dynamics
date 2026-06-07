use rf_core::propagation::{
    DenseVoxelGrid, IonosphereLut, MaterialId, MaterialTable, MultipathModel, PropagationInputs,
    PropagationSolver, RayleighFading, SolarActivity, SolverConfig,
};

const FREQ_HZ: f64 = 145.0e6; // 2 m wavelength: nulls every metre or so.

fn main() {
    let materials = MaterialTable::with_defaults();
    let iono = IonosphereLut::for_activity(SolarActivity::Medium);

    println!("open ground, receiver swept from 40 m to 80 m, step 0.5 m");
    println!("  {:>6} {:>9} {:>9} {:>9}", "x_m", "none", "tworay", "multi");
    sweep(&open_ground(), &materials, &iono, 40.0, 80.0, 0.5);

    println!();
    println!("iron corridor, receiver swept from 40 m to 80 m, step 0.5 m");
    println!("  {:>6} {:>9} {:>9} {:>9}", "x_m", "none", "tworay", "multi");
    sweep(&iron_corridor(), &materials, &iono, 40.0, 80.0, 0.5);
}

fn sweep(
    grid: &DenseVoxelGrid,
    materials: &MaterialTable,
    iono: &IonosphereLut,
    from: f32,
    to: f32,
    step: f32,
) {
    let mut x = from;
    while x <= to {
        let none = level(grid, materials, iono, MultipathModel::None, x);
        let two = level(grid, materials, iono, MultipathModel::TwoRay, x);
        let multi = level(grid, materials, iono, MultipathModel::Multipath, x);
        println!("  {:>6.1} {:>9.1} {:>9.1} {:>9.1}", x, none, two, multi);
        x += step;
    }
}

/// Received level in dB below the transmitter (negative of total path
/// loss), so a higher number means a stronger signal.
fn level(
    grid: &DenseVoxelGrid,
    materials: &MaterialTable,
    iono: &IonosphereLut,
    model: MultipathModel,
    rx_x: f32,
) -> f32 {
    let mut cfg = SolverConfig::default();
    cfg.multipath_model = model;
    let solver = PropagationSolver::new(grid, materials, iono, cfg);
    // Zero coherence so the stochastic TwoRay fade does not mask the
    // deterministic interference; the sweep should show geometry, not
    // random draws.
    let mut fading = RayleighFading::new(0xC0FFEE, 0.0);
    let inputs = PropagationInputs {
        tx_pos: [0.0, 5.0, 0.0],
        rx_pos: [rx_x, 5.0, 0.0],
        tx_vel: [0.0; 3],
        rx_vel: [0.0; 3],
        frequency_hz: FREQ_HZ,
        tx_gain: 1.0,
        rx_gain: 1.0,
        local_hour: 12.0,
    };
    let pl = solver.solve(inputs, &mut fading);
    if pl.is_closed() {
        -999.0
    } else {
        -pl.db
    }
}

fn open_ground() -> DenseVoxelGrid {
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -20], [120, 50, 40]);
    for x in -8..110 {
        for z in -18..18 {
            for y in -4..0 {
                g.set(x, y, z, MaterialId(5)); // dirt
            }
        }
    }
    g
}

fn iron_corridor() -> DenseVoxelGrid {
    let mut g = DenseVoxelGrid::new(1.0, [-10, -10, -20], [120, 50, 40]);
    for x in -8..110 {
        for y in -4..14 {
            for z in -8..8 {
                let interior = (1..=9).contains(&y) && (-4..=4).contains(&z);
                if !interior {
                    g.set(x, y, z, MaterialId(7)); // iron walls, floor, ceiling
                }
            }
        }
    }
    g
}