//! Compute backend selection.
//!
//! Each accelerated workload has a CPU implementation that is always
//! present and a GPU implementation that is optional. `ComputeContext`
//! owns the optional GPU pipelines, a per-workload `ComputePolicy`, and
//! per-workload dispatch counters. It dispatches each call to one backend
//! with a CPU fallback when the GPU is absent, disabled, or rejects the
//! request.
//!
//! Workloads are identified by `ComputeWorkload`. The Java configurator
//! sets the policy and threshold per workload and reads the counters.
//! Selecting a backend never changes the simulation: the CPU path is the
//! reference and the GPU path matches it within a self-test validated
//! tolerance. Each GPU pipeline is validated once at construction against
//! its CPU reference; on divergence the GPU path is dropped.
//!
//! Workloads:
//!   * ZoomDft     spectrum analyzer zoom DFT.
//!   * Propagation batched voxel absorption raycast.

mod gpu_raycast;
mod gpu_voxel;
mod raycast;

use ash::vk;

use crate::dsp::zoom_dft::{bin_sums_cpu, finalize_db, hann_window};
use crate::dsp::zoom_dft_gpu::GpuZoomDft;
use crate::propagation::material::{MaterialId, MaterialTable};
use crate::propagation::voxel::ChunkedVoxelGrid;
use crate::types::iq::Iq;
use crate::vulkan::VulkanDevice;

pub use gpu_raycast::{GPU_RAYCAST_MAX_MATERIALS, GPU_RAYCAST_MAX_QUERIES};
pub use gpu_voxel::{GPU_GRID_DIR_CAPACITY, GPU_GRID_MAX_CHUNKS};
pub use raycast::{pack_query, raycast_batch_cpu, QUERY_STRIDE};

use gpu_raycast::GpuRaycast;
use gpu_voxel::GpuVoxelGrid;
use raycast::raycast_batch_cpu as cpu_raycast_batch;

/// Number of distinct accelerated workloads.
pub const WORKLOAD_COUNT: usize = 2;

/// Identifier of an accelerated workload. The discriminant is the FFI
/// contract; never reorder, only append.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum ComputeWorkload {
    /// Zoom DFT for the spectrum analyzer.
    ZoomDft = 0,
    /// Batched voxel absorption raycast.
    Propagation = 1,
}

impl ComputeWorkload {
    /// Reverse mapping from the FFI discriminant.
    pub fn from_u32(v: u32) -> Option<Self> {
        match v {
            0 => Some(Self::ZoomDft),
            1 => Some(Self::Propagation),
            _ => None,
        }
    }

    fn index(self) -> usize {
        self as usize
    }
}

/// Which backend ran a given call.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum ComputeBackend {
    /// Ran on the CPU reference path.
    Cpu,
    /// Ran on the Vulkan compute path.
    Gpu,
}

/// Per-workload backend selection policy.
#[derive(Copy, Clone, Debug)]
pub enum ComputePolicy {
    /// Always use the CPU path.
    ForceCpu,
    /// Use the GPU path when available, else fall back to CPU.
    ForceGpu,
    /// Use the GPU path when available and the work estimate is at least
    /// `min_work`, else CPU.
    Auto { min_work: u64 },
}

impl Default for ComputePolicy {
    fn default() -> Self {
        Self::Auto { min_work: 16_384 }
    }
}

impl ComputePolicy {
    /// Build from the FFI mode code and threshold. Unknown modes are Auto.
    pub fn from_mode(mode: u32, min_work: u64) -> Self {
        match mode {
            1 => Self::ForceCpu,
            2 => Self::ForceGpu,
            _ => Self::Auto { min_work },
        }
    }
}

/// Per-workload dispatch counters. Layout matches hd_compute_stats_t.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct ComputeStats {
    pub cpu_calls: u64,
    pub gpu_calls: u64,
    pub fallback_calls: u64,
    pub last_backend: u32,
    pub gpu_available: u32,
}

const _: () = {
    assert!(core::mem::size_of::<ComputeStats>() == 32);
    assert!(core::mem::align_of::<ComputeStats>() == 8);
};

/// Largest input sample count the GPU zoom DFT buffer is sized for.
pub const ZOOM_MAX_N: usize = 2048;
/// Largest bin count the GPU zoom DFT buffer is sized for.
pub const ZOOM_MAX_BINS: usize = 256;

/// Relative error gate for the GPU self-tests.
const SELF_TEST_REL_TOL: f32 = 1e-2;
/// Absolute dB gate for the raycast self-test.
const RAYCAST_SELF_TEST_TOL_DB: f32 = 0.5;

/// Owns the optional GPU pipelines, the policies, and the counters.
pub struct ComputeContext {
    policies: [ComputePolicy; WORKLOAD_COUNT],
    stats: [ComputeStats; WORKLOAD_COUNT],
    gpu_zoom: Option<GpuZoomDft>,
    gpu_voxel: Option<GpuVoxelGrid>,
    gpu_raycast: Option<GpuRaycast>,
    windowed_scratch: Vec<Iq>,
    sums_scratch: Vec<Iq>,
}

impl ComputeContext {
    /// Build the context. GPU pipelines are built and self-tested when
    /// `enable_gpu` is true; any failure is non fatal and that workload
    /// falls back to CPU.
    pub fn new(
        enable_gpu: bool,
        device: &VulkanDevice,
        pipeline_cache: vk::PipelineCache,
        descriptor_pool: vk::DescriptorPool,
    ) -> Self {
        let gpu_zoom = if enable_gpu {
            match GpuZoomDft::new(device, pipeline_cache, descriptor_pool, ZOOM_MAX_N, ZOOM_MAX_BINS) {
                Ok(mut g) => match Self::validate_zoom(&mut g) {
                    Ok(()) => Some(g),
                    Err(m) => {
                        eprintln!("[rf-core] GPU zoom DFT self-test failed ({m}); using CPU");
                        None
                    }
                },
                Err(e) => {
                    eprintln!("[rf-core] GPU zoom DFT unavailable, using CPU: {e}");
                    None
                }
            }
        } else {
            None
        };

        let (gpu_voxel, gpu_raycast) = if enable_gpu {
            match Self::build_raycast(device, pipeline_cache, descriptor_pool) {
                Ok(pair) => pair,
                Err(e) => {
                    eprintln!("[rf-core] GPU raycast unavailable, using CPU: {e}");
                    (None, None)
                }
            }
        } else {
            (None, None)
        };

        let mut stats = [ComputeStats::default(); WORKLOAD_COUNT];
        stats[ComputeWorkload::ZoomDft.index()].gpu_available = u32::from(gpu_zoom.is_some());
        stats[ComputeWorkload::Propagation.index()].gpu_available = u32::from(gpu_raycast.is_some());

        Self {
            policies: [ComputePolicy::default(); WORKLOAD_COUNT],
            stats,
            gpu_zoom,
            gpu_voxel,
            gpu_raycast,
            windowed_scratch: Vec::new(),
            sums_scratch: Vec::new(),
        }
    }

    fn build_raycast(
        device: &VulkanDevice,
        pipeline_cache: vk::PipelineCache,
        descriptor_pool: vk::DescriptorPool,
    ) -> crate::error::Result<(Option<GpuVoxelGrid>, Option<GpuRaycast>)> {
        let mut grid = GpuVoxelGrid::new(device)?;
        let mut rc = GpuRaycast::new(device, pipeline_cache, descriptor_pool)?;
        match Self::validate_raycast(&mut grid, &mut rc) {
            Ok(()) => Ok((Some(grid), Some(rc))),
            Err(m) => {
                eprintln!("[rf-core] GPU raycast self-test failed ({m}); using CPU");
                Ok((None, None))
            }
        }
    }

    fn validate_zoom(gpu: &mut GpuZoomDft) -> core::result::Result<(), String> {
        const N: usize = 512;
        const BINS: usize = 192;
        const FS: f32 = 48_000.0;
        const SPAN: f32 = 5_000.0;
        const TONE_HZ: f32 = 1_000.0;
        let hann = hann_window(N);
        let mut iq = vec![Iq::ZERO; N];
        for k in 0..N {
            let ph = core::f32::consts::TAU * TONE_HZ * k as f32 / FS;
            iq[k] = Iq::new(ph.cos(), ph.sin());
        }
        let mut windowed = vec![Iq::ZERO; N];
        for k in 0..N {
            windowed[k] = Iq::new(iq[k].i * hann[k], iq[k].q * hann[k]);
        }
        let mut cpu = vec![Iq::ZERO; BINS];
        bin_sums_cpu(&iq, &hann, N, SPAN, FS, BINS, &mut cpu);
        let mut got = vec![Iq::ZERO; BINS];
        gpu.execute(&windowed, SPAN, FS, BINS, &mut got).map_err(|e| format!("{e}"))?;
        let mut max_mag = 1e-12_f32;
        let mut max_err = 0.0_f32;
        for b in 0..BINS {
            max_mag = max_mag.max(cpu[b].magnitude());
            let de = ((cpu[b].i - got[b].i).powi(2) + (cpu[b].q - got[b].q).powi(2)).sqrt();
            max_err = max_err.max(de);
        }
        let rel = max_err / max_mag;
        if rel > SELF_TEST_REL_TOL {
            return Err(format!("relative error {rel:.3e}"));
        }
        Ok(())
    }

    fn validate_raycast(
        grid: &mut GpuVoxelGrid,
        rc: &mut GpuRaycast,
    ) -> core::result::Result<(), String> {
        let materials = MaterialTable::with_defaults();
        let mut cells = ChunkedVoxelGrid::new(1.0);
        // Stone wall (id 1) across the path.
        for x in 5..8 {
            for y in 0..4 {
                for z in -2..2 {
                    cells.set(x, y, z, MaterialId(1));
                }
            }
        }
        if !grid.sync(&cells) {
            return Err("grid sync rejected".to_string());
        }
        // One ray through the wall, one in clear air.
        let mut queries = vec![0.0_f32; 2 * QUERY_STRIDE];
        pack_query(&mut queries, 0, [0.0, 1.0, 0.0], [20.0, 1.0, 0.0]);
        pack_query(&mut queries, 1, [0.0, 1.0, 10.0], [20.0, 1.0, 10.0]);
        let freq = 100.0e6_f64; // reference frequency: power law bypassed, exact
        let budget = 300.0_f32;

        let mut cpu = [0.0_f32; 2];
        cpu_raycast_batch(&cells, &materials, &queries, 2, freq, budget, &mut cpu);
        let mut got = [0.0_f32; 2];
        rc.execute(grid, &materials, &queries, 2, freq, budget, &mut got)
            .map_err(|e| format!("{e}"))?;
        for i in 0..2 {
            if (cpu[i] - got[i]).abs() > RAYCAST_SELF_TEST_TOL_DB {
                return Err(format!("ray {i} cpu {:.3} gpu {:.3}", cpu[i], got[i]));
            }
        }
        Ok(())
    }

    /// True when the GPU zoom DFT path is present.
    pub fn zoom_gpu_available(&self) -> bool {
        self.gpu_zoom.is_some()
    }

    /// True when the GPU raycast path is present.
    pub fn raycast_gpu_available(&self) -> bool {
        self.gpu_raycast.is_some()
    }

    /// Set the policy for one workload.
    pub fn set_policy(&mut self, workload: ComputeWorkload, policy: ComputePolicy) {
        self.policies[workload.index()] = policy;
    }

    /// Read the counters for one workload.
    pub fn stats(&self, workload: ComputeWorkload) -> ComputeStats {
        self.stats[workload.index()]
    }

    /// Zoom DFT dispatcher.
    pub fn zoom_dft(
        &mut self,
        iq: &[Iq],
        n: usize,
        span_hz: f32,
        fs_hz: f32,
        bins: usize,
        out_db: &mut [f32],
    ) -> ComputeBackend {
        if n == 0 || bins == 0 || iq.len() < n || out_db.len() < bins {
            return ComputeBackend::Cpu;
        }
        let idx = ComputeWorkload::ZoomDft.index();
        let hann = hann_window(n);
        let work = (n as u64) * (bins as u64);
        let want_gpu = match self.policies[idx] {
            ComputePolicy::ForceCpu => false,
            ComputePolicy::ForceGpu => self.gpu_zoom.is_some(),
            ComputePolicy::Auto { min_work } => self.gpu_zoom.is_some() && work >= min_work,
        };
        if self.sums_scratch.len() < bins {
            self.sums_scratch.resize(bins, Iq::ZERO);
        }
        let mut ran_gpu = false;
        if want_gpu {
            if self.windowed_scratch.len() < n {
                self.windowed_scratch.resize(n, Iq::ZERO);
            }
            for k in 0..n {
                self.windowed_scratch[k] = Iq::new(iq[k].i * hann[k], iq[k].q * hann[k]);
            }
            let gpu = self.gpu_zoom.as_mut().expect("checked above");
            match gpu.execute(&self.windowed_scratch[..n], span_hz, fs_hz, bins, &mut self.sums_scratch[..bins]) {
                Ok(()) => ran_gpu = true,
                Err(_) => self.stats[idx].fallback_calls += 1,
            }
        }
        if !ran_gpu {
            bin_sums_cpu(iq, &hann, n, span_hz, fs_hz, bins, &mut self.sums_scratch[..bins]);
        }
        finalize_db(&self.sums_scratch[..bins], n, &hann, span_hz, bins, out_db);
        self.record(idx, ran_gpu)
    }

    /// Raycast batch dispatcher. `grid` is the source voxel grid, `queries`
    /// holds `count` packed rays, `out` receives `count` absorption dB. The
    /// work estimate is the ray count, a proxy for the GPU submission
    /// breakeven; the per-ray voxel count is not known up front.
    pub fn raycast_batch(
        &mut self,
        grid: &ChunkedVoxelGrid,
        materials: &MaterialTable,
        queries: &[f32],
        count: usize,
        frequency_hz: f64,
        budget_db: f32,
        out: &mut [f32],
    ) -> ComputeBackend {
        if count == 0 || queries.len() < count * QUERY_STRIDE || out.len() < count {
            return ComputeBackend::Cpu;
        }
        let idx = ComputeWorkload::Propagation.index();
        let work = count as u64;
        let gpu_present = self.gpu_raycast.is_some() && self.gpu_voxel.is_some();
        let want_gpu = match self.policies[idx] {
            ComputePolicy::ForceCpu => false,
            ComputePolicy::ForceGpu => gpu_present,
            ComputePolicy::Auto { min_work } => gpu_present && work >= min_work,
        };

        let mut ran_gpu = false;
        if want_gpu && count <= GPU_RAYCAST_MAX_QUERIES {
            let voxel = self.gpu_voxel.as_mut().expect("checked");
            if voxel.sync(grid) {
                let rc = self.gpu_raycast.as_mut().expect("checked");
                match rc.execute(voxel, materials, queries, count, frequency_hz, budget_db, &mut out[..count]) {
                    Ok(()) => ran_gpu = true,
                    Err(_) => self.stats[idx].fallback_calls += 1,
                }
            } else {
                // Grid too large for the GPU mirror this call.
                self.stats[idx].fallback_calls += 1;
            }
        } else if want_gpu {
            self.stats[idx].fallback_calls += 1;
        }

        if !ran_gpu {
            cpu_raycast_batch(grid, materials, queries, count, frequency_hz, budget_db, &mut out[..count]);
        }
        self.record(idx, ran_gpu)
    }

    fn record(&mut self, idx: usize, ran_gpu: bool) -> ComputeBackend {
        if ran_gpu {
            self.stats[idx].gpu_calls += 1;
            self.stats[idx].last_backend = 1;
            ComputeBackend::Gpu
        } else {
            self.stats[idx].cpu_calls += 1;
            self.stats[idx].last_backend = 0;
            ComputeBackend::Cpu
        }
    }
}