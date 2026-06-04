//! Voxel grid abstraction and 3D DDA raycaster.
//!
//! The DDA algorithm is Amanatides and Woo, 1987. It walks one
//! voxel per step along a ray, choosing at each step the axis whose
//! `tMax` value is currently smallest. This produces a tight list
//! of every voxel the ray touches without overshoot. The
//! implementation here is the standard one with a few changes:
//!   * The ray uses absolute world coordinates in metres. The grid
//!     converts to integer voxel coordinates internally.
//!   * The traversal is bounded by a maximum distance; rays that
//!     exit before reaching the receiver report a partial sum.
//!   * Each voxel returns (`MaterialId`, segment length in metres)
//!     to the caller through a visitor closure, so the caller can
//!     terminate early when the accumulated loss exceeds a budget.

use crate::propagation::material::{MaterialId, MaterialTable};

/// Trait covering both the dense (test) and chunked (production)
/// implementations. Implementors return `MaterialId::AIR` for any
/// voxel outside their storage so traversal degenerates gracefully
/// past the world edge.
pub trait VoxelGrid {
    /// Side length of one voxel, in metres. The engine assumes
    /// cubic voxels.
    fn voxel_size_m(&self) -> f32;

    /// Material at integer voxel coordinates. Out of bounds returns
    /// air.
    fn material_at(&self, x: i32, y: i32, z: i32) -> MaterialId;
}

/// Outcome of a raycast that terminated early.
#[derive(Copy, Clone, Debug, Default)]
pub struct RaycastHit {
    /// Distance from the ray origin to the termination point, in
    /// metres. Equal to `ray_length` when the cast completed.
    pub distance_m: f32,
    /// Number of voxels visited.
    pub voxels: u32,
}

/// Walk the ray and call `visit` on each voxel. `visit` receives
/// the material and the length in metres the ray spent inside that
/// voxel, and returns `true` to stop traversal. The function
/// returns the termination distance and visit count.
///
/// Coordinates are in world metres. The grid origin is the world
/// origin; integer voxel `(x, y, z)` covers
/// `[x*vs, (x+1)*vs) x [y*vs, (y+1)*vs) x [z*vs, (z+1)*vs)`.
pub fn traverse<G: VoxelGrid, F: FnMut(MaterialId, f32) -> bool>(
    grid: &G,
    origin: [f32; 3],
    direction: [f32; 3],
    ray_length: f32,
    mut visit: F,
) -> RaycastHit {
    if ray_length <= 0.0 {
        return RaycastHit::default();
    }
    let vs = grid.voxel_size_m();
    debug_assert!(vs > 0.0);

    // Normalise the direction; an unnormalised one is a caller bug
    // because the algorithm interprets it as the unit vector for
    // parametric distance.
    let len = (direction[0] * direction[0]
        + direction[1] * direction[1]
        + direction[2] * direction[2])
        .sqrt();
    if len <= 0.0 {
        return RaycastHit::default();
    }
    let dir = [direction[0] / len, direction[1] / len, direction[2] / len];

    // Voxel that contains the origin.
    let mut vx = (origin[0] / vs).floor() as i32;
    let mut vy = (origin[1] / vs).floor() as i32;
    let mut vz = (origin[2] / vs).floor() as i32;

    // Step direction along each axis.
    let step_x: i32 = if dir[0] > 0.0 { 1 } else if dir[0] < 0.0 { -1 } else { 0 };
    let step_y: i32 = if dir[1] > 0.0 { 1 } else if dir[1] < 0.0 { -1 } else { 0 };
    let step_z: i32 = if dir[2] > 0.0 { 1 } else if dir[2] < 0.0 { -1 } else { 0 };

    // Distance the ray has to travel to cross one voxel along each
    // axis. Infinity for axes with zero direction component, which
    // simply means the traversal never advances along that axis.
    let t_delta_x = if dir[0] != 0.0 { (vs / dir[0]).abs() } else { f32::INFINITY };
    let t_delta_y = if dir[1] != 0.0 { (vs / dir[1]).abs() } else { f32::INFINITY };
    let t_delta_z = if dir[2] != 0.0 { (vs / dir[2]).abs() } else { f32::INFINITY };

    // Distance from the origin to the first voxel boundary along
    // each axis.
    let next_boundary = |o: f32, v: i32, step: i32| -> f32 {
        if step > 0 {
            ((v + 1) as f32) * vs - o
        } else if step < 0 {
            o - (v as f32) * vs
        } else {
            f32::INFINITY
        }
    };
    let mut t_max_x = if dir[0] != 0.0 {
        next_boundary(origin[0], vx, step_x) / dir[0].abs()
    } else {
        f32::INFINITY
    };
    let mut t_max_y = if dir[1] != 0.0 {
        next_boundary(origin[1], vy, step_y) / dir[1].abs()
    } else {
        f32::INFINITY
    };
    let mut t_max_z = if dir[2] != 0.0 {
        next_boundary(origin[2], vz, step_z) / dir[2].abs()
    } else {
        f32::INFINITY
    };

    let mut t_prev = 0.0_f32;
    let mut count: u32 = 0;

    loop {
        // Length of the ray segment inside the current voxel.
        let t_next = t_max_x.min(t_max_y.min(t_max_z)).min(ray_length);
        let seg = t_next - t_prev;
        if seg > 0.0 {
            let mid_id = grid.material_at(vx, vy, vz);
            count += 1;
            if visit(mid_id, seg) {
                return RaycastHit { distance_m: t_next, voxels: count };
            }
        }
        t_prev = t_next;
        if t_next >= ray_length {
            return RaycastHit { distance_m: ray_length, voxels: count };
        }

        // Advance into the next voxel along the axis with the
        // smallest t_max.
        if t_max_x <= t_max_y && t_max_x <= t_max_z {
            vx += step_x;
            t_max_x += t_delta_x;
        } else if t_max_y <= t_max_z {
            vy += step_y;
            t_max_y += t_delta_y;
        } else {
            vz += step_z;
            t_max_z += t_delta_z;
        }
    }
}

/// Sum absorption along a ray using the material table. Returns the
/// accumulated attenuation in decibels at the given frequency.
/// Stops early if the budget is exceeded; the engine treats anything
/// above ~120 dB as opaque, and the early-out keeps long underground
/// paths cheap.
pub fn raycast_absorption_db<G: VoxelGrid>(
    grid: &G,
    materials: &MaterialTable,
    origin: [f32; 3],
    target: [f32; 3],
    frequency_hz: f64,
    budget_db: f32,
) -> f32 {
    let dir = [target[0] - origin[0], target[1] - origin[1], target[2] - origin[2]];
    let len =
        (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).sqrt();
    if len <= 0.0 {
        return 0.0;
    }
    let mut total_db = 0.0_f32;
    traverse(grid, origin, dir, len, |id, seg_m| {
        let mat = materials.get(id);
        total_db += mat.attenuation_db_per_m(frequency_hz) * seg_m;
        total_db >= budget_db
    });
    total_db.min(budget_db)
}

/// Dense test grid backed by a Vec. Allocates `dims.0 * dims.1 *
/// dims.2` entries up front. Use only for tests and examples; the
/// production block path uses `ChunkedVoxelGrid`.
#[derive(Clone, Debug)]
pub struct DenseVoxelGrid {
    voxel_size: f32,
    origin_voxel: [i32; 3],
    dims: [u32; 3],
    cells: Vec<MaterialId>,
}

impl DenseVoxelGrid {
    /// Build an all air grid with the given origin and dimensions.
    pub fn new(voxel_size: f32, origin_voxel: [i32; 3], dims: [u32; 3]) -> Self {
        let n = dims[0] as usize * dims[1] as usize * dims[2] as usize;
        Self {
            voxel_size,
            origin_voxel,
            dims,
            cells: vec![MaterialId::AIR; n],
        }
    }

    /// Write a material at the given voxel. Coordinates are in
    /// absolute world voxel units, not relative to the grid origin.
    pub fn set(&mut self, x: i32, y: i32, z: i32, id: MaterialId) {
        if let Some(idx) = self.linear_index(x, y, z) {
            self.cells[idx] = id;
        }
    }

    fn linear_index(&self, x: i32, y: i32, z: i32) -> Option<usize> {
        let lx = x - self.origin_voxel[0];
        let ly = y - self.origin_voxel[1];
        let lz = z - self.origin_voxel[2];
        if lx < 0 || ly < 0 || lz < 0 {
            return None;
        }
        let (dx, dy, dz) = (self.dims[0] as i32, self.dims[1] as i32, self.dims[2] as i32);
        if lx >= dx || ly >= dy || lz >= dz {
            return None;
        }
        Some(((lz * dy + ly) * dx + lx) as usize)
    }
}

impl VoxelGrid for DenseVoxelGrid {
    fn voxel_size_m(&self) -> f32 {
        self.voxel_size
    }
    fn material_at(&self, x: i32, y: i32, z: i32) -> MaterialId {
        match self.linear_index(x, y, z) {
            Some(idx) => self.cells[idx],
            None => MaterialId::AIR,
        }
    }
}

/// Sparse production grid backed by a hash map of chunks. One chunk
/// holds CHUNK_DIM^3 voxels packed into a Vec. Chunks materialise on
/// first write; reads from unmapped chunks return air.
///
/// `CHUNK_DIM` is fixed at 16 to match Minecraft world chunks on the
/// X and Z axes; the Y axis covers two stacked chunks per world
/// column which is fine for the engine because we do not store
/// per-Y-section chunks separately.
#[derive(Default, Clone, Debug)]
pub struct ChunkedVoxelGrid {
    voxel_size: f32,
    chunks: std::collections::HashMap<[i32; 3], ChunkData>,
    /// Monotonic counter bumped on every voxel write. A GPU mirror records
    /// the generation it synced at and rebuilds when this differs, so the
    /// mirror does not have to track individual block changes.
    generation: u64,
}

/// Side length of one chunk along each axis, in voxels. Public so the GPU
/// mirror can replicate the chunk and local index layout exactly.
pub const CHUNK_DIM: i32 = 16;
const CHUNK_CELLS: usize = (CHUNK_DIM * CHUNK_DIM * CHUNK_DIM) as usize;

#[derive(Clone, Debug)]
struct ChunkData {
    cells: Vec<MaterialId>,
}

impl ChunkData {
    fn new() -> Self {
        Self { cells: vec![MaterialId::AIR; CHUNK_CELLS] }
    }
    fn idx(local_x: i32, local_y: i32, local_z: i32) -> usize {
        ((local_z * CHUNK_DIM + local_y) * CHUNK_DIM + local_x) as usize
    }
}

impl ChunkedVoxelGrid {
    /// Empty grid with the chosen voxel size.
    pub fn new(voxel_size: f32) -> Self {
        Self {
            voxel_size,
            chunks: std::collections::HashMap::new(),
            generation: 0,
        }
    }

    /// Set a single voxel, creating its chunk on demand.
    pub fn set(&mut self, x: i32, y: i32, z: i32, id: MaterialId) {
        let chunk = [x.div_euclid(CHUNK_DIM), y.div_euclid(CHUNK_DIM), z.div_euclid(CHUNK_DIM)];
        let lx = x.rem_euclid(CHUNK_DIM);
        let ly = y.rem_euclid(CHUNK_DIM);
        let lz = z.rem_euclid(CHUNK_DIM);
        let entry = self.chunks.entry(chunk).or_insert_with(ChunkData::new);
        entry.cells[ChunkData::idx(lx, ly, lz)] = id;
        self.generation = self.generation.wrapping_add(1);
    }

    /// Number of currently materialised chunks.
    pub fn chunk_count(&self) -> usize {
        self.chunks.len()
    }

    /// Current write generation. Increases on every `set`.
    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// Iterate occupied chunks as (chunk coordinate, 4096 material cells in
    /// (z*16 + y)*16 + x order). Used by the GPU mirror to upload the grid.
    pub fn iter_chunks(&self) -> impl Iterator<Item = ([i32; 3], &[MaterialId])> + '_ {
        self.chunks.iter().map(|(k, v)| (*k, v.cells.as_slice()))
    }
}

impl VoxelGrid for ChunkedVoxelGrid {
    fn voxel_size_m(&self) -> f32 {
        self.voxel_size
    }
    fn material_at(&self, x: i32, y: i32, z: i32) -> MaterialId {
        let chunk = [x.div_euclid(CHUNK_DIM), y.div_euclid(CHUNK_DIM), z.div_euclid(CHUNK_DIM)];
        match self.chunks.get(&chunk) {
            Some(c) => {
                let lx = x.rem_euclid(CHUNK_DIM);
                let ly = y.rem_euclid(CHUNK_DIM);
                let lz = z.rem_euclid(CHUNK_DIM);
                c.cells[ChunkData::idx(lx, ly, lz)]
            }
            None => MaterialId::AIR,
        }
    }
}