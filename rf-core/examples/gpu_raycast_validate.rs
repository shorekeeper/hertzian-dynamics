//! Validate the GPU batched raycast against the CPU reference on a real
//! voxel grid. Requires a working Vulkan device.
//!
//! Run: cargo run --example gpu_raycast_validate --features gpu_tests
//!
//! Builds a grid with two walls (stone and iron), runs a fan of rays
//! through them on both backends, and reports the maximum absorption
//! difference. At the material reference frequency the two are exact; away
//! from it the material power law introduces a small bounded difference.

use rf_core::compute::{pack_query, raycast_batch_cpu, ComputePolicy, ComputeWorkload, QUERY_STRIDE};
use rf_core::propagation::material::{MaterialId, MaterialTable};
use rf_core::propagation::voxel::ChunkedVoxelGrid;
use rf_core::{RfCore, RfCoreConfig};

fn main() {
    let mut core = RfCore::new(RfCoreConfig::default()).expect("init RfCore");
    core.set_compute_policy(ComputeWorkload::Propagation, ComputePolicy::ForceGpu);

    let materials = MaterialTable::with_defaults();
    let mut grid = ChunkedVoxelGrid::new(1.0);
    for x in 30..33 {
        for y in 0..10 {
            for z in -20..20 {
                grid.set(x, y, z, MaterialId(1)); // stone
            }
        }
    }
    for x in 60..61 {
        for y in 0..10 {
            for z in -20..20 {
                grid.set(x, y, z, MaterialId(7)); // iron
            }
        }
    }

    const COUNT: usize = 64;
    let mut queries = vec![0.0_f32; COUNT * QUERY_STRIDE];
    for i in 0..COUNT {
        let zoff = -10.0 + 20.0 * (i as f32 / COUNT as f32);
        pack_query(&mut queries, i, [0.0, 5.0, zoff], [100.0, 5.0, zoff]);
    }

    for freq in [100.0e6_f64, 145.0e6_f64] {
        let mut cpu = vec![0.0_f32; COUNT];
        raycast_batch_cpu(&grid, &materials, &queries, COUNT, freq, 300.0, &mut cpu);

        let mut gpu = vec![0.0_f32; COUNT];
        core.raycast_batch(&grid, &materials, &queries, COUNT, freq, 300.0, &mut gpu);

        let mut max_err = 0.0_f32;
        for i in 0..COUNT {
            max_err = max_err.max((cpu[i] - gpu[i]).abs());
        }
        let stats = core.compute_stats(ComputeWorkload::Propagation);
        println!(
            "freq {:>6.1} MHz: max abs diff {:.4} dB (gpu_calls {} cpu_calls {} fallback {})",
            freq / 1.0e6,
            max_err,
            stats.gpu_calls,
            stats.cpu_calls,
            stats.fallback_calls
        );
        assert!(max_err < 0.5, "raycast diff too large: {max_err}");
    }
    println!("gpu raycast validated against cpu reference");
}