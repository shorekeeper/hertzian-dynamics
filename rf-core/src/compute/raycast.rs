//! Batched voxel absorption raycast: CPU reference and the shared query
//! packing convention.
//!
//! A raycast query is a ray from origin to target through the voxel grid.
//! The CPU reference sums material absorption along the ray, identical to
//! `propagation::voxel::raycast_absorption_db`. The GPU shader replicates
//! the same traversal. Queries are packed as 8 f32 per ray (origin xyz,
//! one pad, target xyz, one pad) so the buffer layout matches the shader's
//! readonly float array.

use crate::propagation::material::MaterialTable;
use crate::propagation::voxel::{raycast_absorption_db, ChunkedVoxelGrid};

/// Number of f32 per packed query: origin(3) + pad(1) + target(3) + pad(1).
pub const QUERY_STRIDE: usize = 8;

/// Pack a single ray into `out` at element offset `slot * QUERY_STRIDE`.
pub fn pack_query(out: &mut [f32], slot: usize, origin: [f32; 3], target: [f32; 3]) {
    let b = slot * QUERY_STRIDE;
    out[b] = origin[0];
    out[b + 1] = origin[1];
    out[b + 2] = origin[2];
    out[b + 3] = 0.0;
    out[b + 4] = target[0];
    out[b + 5] = target[1];
    out[b + 6] = target[2];
    out[b + 7] = 0.0;
}

/// CPU reference for a batch of queries. `queries` holds `count` packed
/// rays. `out` receives `count` absorption values in dB. Each ray is the
/// same computation the solver runs inline today, so a GPU result that
/// matches this is realism neutral.
pub fn raycast_batch_cpu(
    grid: &ChunkedVoxelGrid,
    materials: &MaterialTable,
    queries: &[f32],
    count: usize,
    frequency_hz: f64,
    budget_db: f32,
    out: &mut [f32],
) {
    debug_assert!(queries.len() >= count * QUERY_STRIDE);
    debug_assert!(out.len() >= count);
    for i in 0..count {
        let b = i * QUERY_STRIDE;
        let origin = [queries[b], queries[b + 1], queries[b + 2]];
        let target = [queries[b + 4], queries[b + 5], queries[b + 6]];
        out[i] = raycast_absorption_db(grid, materials, origin, target, frequency_hz, budget_db);
    }
}