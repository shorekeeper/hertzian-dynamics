//! GPU-resident voxel grid mirror.
//!
//! Mirrors a `ChunkedVoxelGrid` into two host visible storage buffers a
//! compute shader can read: an open-addressing directory keyed by chunk
//! coordinate and a pool of 4096-voxel blocks. The directory hash and
//! probe sequence match the shader in shaders/raycast.comp exactly.
//!
//! Buffers are fixed size, sized for `GPU_GRID_MAX_CHUNKS` occupied chunks.
//! A grid exceeding that is rejected at sync time and the caller falls back
//! to the CPU path. The mirror tracks the source grid generation and only
//! rebuilds when it changes; the rebuild rewrites the directory and the
//! occupied pool regions in place without reallocating.

use ash::vk;

use crate::error::Result;
use crate::propagation::material::MaterialId;
use crate::propagation::voxel::{ChunkedVoxelGrid, CHUNK_DIM}; 
use crate::propagation::voxel::VoxelGrid;
use crate::vulkan::{HostBuffer, MemoryAllocator, VulkanDevice};

/// Directory slot count. Power of two for masking. Holds up to
/// GPU_GRID_MAX_CHUNKS occupied chunks at a load factor of 0.5.
pub const GPU_GRID_DIR_CAPACITY: usize = 4096;

/// Maximum occupied chunks the mirror can hold.
pub const GPU_GRID_MAX_CHUNKS: usize = GPU_GRID_DIR_CAPACITY / 2;

const VOXELS_PER_CHUNK: usize = (CHUNK_DIM * CHUNK_DIM * CHUNK_DIM) as usize;

/// GPU mirror of a chunked voxel grid.
pub struct GpuVoxelGrid {
    dir: HostBuffer,
    pool: HostBuffer,
    voxel_size: f32,
    synced_generation: Option<u64>,
    synced_voxel_size: f32,
}

impl GpuVoxelGrid {
    /// Allocate the fixed directory and pool buffers. The mirror starts
    /// empty; call `sync` before the first raycast.
    pub fn new(device: &VulkanDevice) -> Result<Self> {
        let allocator = MemoryAllocator::new(device);
        let dir_bytes = (GPU_GRID_DIR_CAPACITY * 16) as vk::DeviceSize;
        let pool_bytes = (GPU_GRID_MAX_CHUNKS * VOXELS_PER_CHUNK * 4) as vk::DeviceSize;
        let dir = HostBuffer::new(device, &allocator, dir_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;
        let pool = HostBuffer::new(device, &allocator, pool_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;
        Ok(Self {
            dir,
            pool,
            voxel_size: 1.0,
            synced_generation: None,
            synced_voxel_size: 0.0,
        })
    }

    /// Directory buffer handle and byte range.
    pub fn dir_buffer(&self) -> (vk::Buffer, vk::DeviceSize) {
        (self.dir.handle(), self.dir.size())
    }

    /// Pool buffer handle and byte range.
    pub fn pool_buffer(&self) -> (vk::Buffer, vk::DeviceSize) {
        (self.pool.handle(), self.pool.size())
    }

    /// Directory slot count, for the shader push constant.
    pub fn dir_capacity(&self) -> u32 {
        GPU_GRID_DIR_CAPACITY as u32
    }

    /// Voxel side length in metres.
    pub fn voxel_size(&self) -> f32 {
        self.voxel_size
    }

    /// Rebuild the mirror from `grid` if its generation or voxel size
    /// changed since the last sync. Returns false when the grid has more
    /// than GPU_GRID_MAX_CHUNKS occupied chunks, in which case the mirror
    /// is left untouched and the caller must use the CPU path.
    pub fn sync(&mut self, grid: &ChunkedVoxelGrid) -> bool {
        let genf = grid.generation();
        if self.synced_generation == Some(genf) && self.synced_voxel_size == grid.voxel_size_m() {
            return true;
        }
        if grid.chunk_count() > GPU_GRID_MAX_CHUNKS {
            return false;
        }

        let cap = GPU_GRID_DIR_CAPACITY;
        let mask = (cap - 1) as u64;

        // Clear the directory to the empty sentinel (poolIndex = -1).
        {
            let bytes = self.dir.as_slice_mut();
            let slots = bytemut_i32(bytes, cap * 4);
            for s in 0..cap {
                slots[s * 4] = 0;
                slots[s * 4 + 1] = 0;
                slots[s * 4 + 2] = 0;
                slots[s * 4 + 3] = -1;
            }
        }

        // Insert occupied chunks and write their voxels into the pool. The
        // hash and probe must match shaders/raycast.comp.
        let mut pool_index: usize = 0;
        for (coord, cells) in grid.iter_chunks() {
            let [cx, cy, cz] = coord;
            let h = hash_chunk(cx, cy, cz);
            let mut slot = (h & mask) as usize;
            loop {
                let w = read_i32(self.dir.as_slice(), slot * 4 + 3);
                if w < 0 {
                    break;
                }
                slot = ((slot as u64 + 1) & mask) as usize;
            }
            {
                let bytes = self.dir.as_slice_mut();
                let slots = bytemut_i32(bytes, cap * 4);
                slots[slot * 4] = cx;
                slots[slot * 4 + 1] = cy;
                slots[slot * 4 + 2] = cz;
                slots[slot * 4 + 3] = pool_index as i32;
            }
            write_pool_chunk(self.pool.as_slice_mut(), pool_index, cells);
            pool_index += 1;
        }

        self.voxel_size = grid.voxel_size_m();
        self.synced_generation = Some(genf);
        self.synced_voxel_size = grid.voxel_size_m();
        true
    }
}

fn hash_chunk(cx: i32, cy: i32, cz: i32) -> u64 {
    let h = (cx as u32)
        .wrapping_mul(73856093)
        ^ (cy as u32).wrapping_mul(19349663)
        ^ (cz as u32).wrapping_mul(83492791);
    h as u64
}

fn write_pool_chunk(pool_bytes: &mut [u8], pool_index: usize, cells: &[MaterialId]) {
    let base = pool_index * VOXELS_PER_CHUNK;
    let dst = bytemut_u32(pool_bytes, (base + VOXELS_PER_CHUNK) * 1);
    for (i, c) in cells.iter().enumerate().take(VOXELS_PER_CHUNK) {
        dst[base + i] = c.0 as u32;
    }
}

// Helpers reinterpreting the host buffer byte slices as typed slices. The
// host buffers are 4 byte aligned (Vulkan host visible memory) so i32/u32
// views are valid.

fn bytemut_i32(bytes: &mut [u8], len_words: usize) -> &mut [i32] {
    // SAFETY: bytes is host visible memory with at least 4*len_words bytes
    // and 4 byte alignment; i32 has size 4 align 4.
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr() as *mut i32, len_words) }
}

fn bytemut_u32(bytes: &mut [u8], len_words: usize) -> &mut [u32] {
    // SAFETY: see bytemut_i32.
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr() as *mut u32, len_words) }
}

fn read_i32(bytes: &[u8], word: usize) -> i32 {
    let o = word * 4;
    i32::from_ne_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]])
}