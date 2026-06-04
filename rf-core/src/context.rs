//! Top level engine facade.
//!
//! `RfCore` owns every long lived Vulkan handle plus the optional compute
//! backend, and is the single object the JNI layer exposes to the JVM.
//! The Java side receives an opaque pointer to a boxed `RfCore`, holds it
//! across calls, and tears it down on world unload.
//!
//! Field declaration order is significant. Rust drops fields in
//! declaration order, so the compute context drops first (it destroys its
//! GPU pipeline and buffers using a cloned device handle), then the
//! descriptor pool and pipeline cache release their handles, then the
//! device, then the instance. The compute context must outlive nothing it
//! depends on, so it leads the field list.

use crate::compute::{ComputeContext, ComputePolicy, ComputeStats, ComputeWorkload};
use crate::error::Result;
use crate::types::iq::Iq;
use crate::vulkan::{
    descriptor::{DescriptorPool, DescriptorPoolCapacity},
    device::VulkanDevice,
    instance::VulkanInstance,
    pipeline::PipelineCache,
};

/// Construction parameters. Every field has a default matching a typical
/// desktop GPU.
#[derive(Clone, Debug)]
pub struct RfCoreConfig {
    /// Application name passed to `vk::ApplicationInfo`. Some drivers emit
    /// per application performance tweaks keyed off this string.
    pub app_name: String,
    /// Request `shaderFloat64` from the physical device. Disable for
    /// maximum host compatibility. The fast DSP kernels never use it.
    pub require_float64: bool,
    /// Sizing of the global descriptor pool.
    pub descriptor_pool: DescriptorPoolCapacity,
    /// Optional pipeline cache blob from a previous session.
    pub pipeline_cache_blob: Option<Vec<u8>>,
    /// Build the GPU compute backend. When false every accelerated
    /// workload runs on the CPU. When true the backend is built if the
    /// device and pipelines come up; failure to build the GPU path is not
    /// fatal and falls back to CPU.
    pub enable_gpu: bool,
}

impl Default for RfCoreConfig {
    fn default() -> Self {
        Self {
            app_name: "Hertzian Dynamics".to_string(),
            require_float64: false,
            descriptor_pool: DescriptorPoolCapacity::default(),
            pipeline_cache_blob: None,
            enable_gpu: true,
        }
    }
}

/// Engine facade. All Vulkan handles and the compute backend are owned
/// here.
pub struct RfCore {
    // Drop order, top to bottom:
    //   1. compute releases its GPU pipeline, buffers, and descriptor set.
    //   2. descriptor_pool releases its vk::DescriptorPool.
    //   3. pipeline_cache releases its vk::PipelineCache.
    //   4. device waits idle, releases its vk::Device.
    //   5. instance releases its vk::Instance and the entry library.
    compute: ComputeContext,
    descriptor_pool: DescriptorPool,
    pipeline_cache: PipelineCache,
    device: VulkanDevice,
    instance: VulkanInstance,
}

impl RfCore {
    /// Build a fresh engine context. Allocates a Vulkan instance, picks
    /// the best physical device, creates a compute logical device, a
    /// pipeline cache, a descriptor pool, and the compute backend. Any
    /// Vulkan failure unwinds cleanly; GPU compute backend failure is
    /// contained and falls back to CPU.
    pub fn new(config: RfCoreConfig) -> Result<Self> {
        let instance = VulkanInstance::new(&config.app_name)?;
        let device = VulkanDevice::pick(&instance, config.require_float64)?;
        let pipeline_cache = PipelineCache::new(&device, config.pipeline_cache_blob.as_deref())?;
        let descriptor_pool = DescriptorPool::new(&device, config.descriptor_pool)?;
        let compute = ComputeContext::new(
            config.enable_gpu,
            &device,
            pipeline_cache.handle(),
            descriptor_pool.handle(),
        );

        Ok(Self {
            compute,
            descriptor_pool,
            pipeline_cache,
            device,
            instance,
        })
    }

    /// Borrow the instance. Mostly diagnostic; sub modules use it directly.
    pub fn instance(&self) -> &VulkanInstance {
        &self.instance
    }

    /// Borrow the device.
    pub fn device(&self) -> &VulkanDevice {
        &self.device
    }

    /// Borrow the shared pipeline cache.
    pub fn pipeline_cache(&self) -> &PipelineCache {
        &self.pipeline_cache
    }

    /// Borrow the shared descriptor pool.
    pub fn descriptor_pool(&self) -> &DescriptorPool {
        &self.descriptor_pool
    }

    /// Borrow the compute backend.
    pub fn compute(&self) -> &ComputeContext {
        &self.compute
    }

    /// Mutably borrow the compute backend.
    pub fn compute_mut(&mut self) -> &mut ComputeContext {
        &mut self.compute
    }

    /// Set the backend policy for one workload.
    pub fn set_compute_policy(&mut self, workload: ComputeWorkload, policy: ComputePolicy) {
        self.compute.set_policy(workload, policy);
    }

    /// Read the dispatch counters for one workload.
    pub fn compute_stats(&self, workload: ComputeWorkload) -> ComputeStats {
        self.compute.stats(workload)
    }

    /// Run the analyzer zoom DFT. `iq` holds at least `n` complex samples,
    /// `bins` per-bin magnitudes in dB are written to `out_db`. Dispatches
    /// to the GPU backend when available and to the CPU otherwise.
    pub fn zoom_dft(
        &mut self,
        iq: &[Iq],
        n: usize,
        span_hz: f32,
        fs_hz: f32,
        bins: usize,
        out_db: &mut [f32],
    ) {
        self.compute.zoom_dft(iq, n, span_hz, fs_hz, bins, out_db);
    }

    /// Run a batched voxel absorption raycast. `grid` is the source grid,
    /// `queries` holds `count` packed rays (8 f32 each: origin xyz, pad,
    /// target xyz, pad), `out` receives `count` absorption values in dB.
    /// Dispatches to the GPU backend when available and selected, else CPU.
    pub fn raycast_batch(
        &mut self,
        grid: &crate::propagation::voxel::ChunkedVoxelGrid,
        materials: &crate::propagation::material::MaterialTable,
        queries: &[f32],
        count: usize,
        frequency_hz: f64,
        budget_db: f32,
        out: &mut [f32],
    ) {
        self.compute
            .raycast_batch(grid, materials, queries, count, frequency_hz, budget_db, out);
    }
}