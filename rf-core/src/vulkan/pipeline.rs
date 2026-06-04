//! Pipeline cache.
//!
//! Compiling a compute pipeline is expensive. The Vulkan pipeline
//! cache lets the driver reuse intermediate compilation results
//! across pipelines and across runs. We keep a single global cache,
//! optionally seeded with a blob loaded from disk and re saved at
//! shutdown. Disk persistence is wired.

use ash::vk;

use crate::error::{vk_err, Result};
use crate::vulkan::device::VulkanDevice;

/// Owning Vulkan pipeline cache. Destroys on Drop.
pub struct PipelineCache {
    device: ash::Device,
    handle: vk::PipelineCache,
}

impl PipelineCache {
    /// Create an empty cache, or initialise from previously saved
    /// bytes. The driver silently discards the seed if it was
    /// produced by an incompatible device.
    pub fn new(device: &VulkanDevice, initial_data: Option<&[u8]>) -> Result<Self> {
        let mut info = vk::PipelineCacheCreateInfo::default();
        if let Some(data) = initial_data {
            info = info.initial_data(data);
        }
        // SAFETY: info pointers reference local stack data alive for
        // the duration of the call.
        let handle = unsafe { device.raw().create_pipeline_cache(&info, None) }
            .map_err(|r| vk_err("vkCreatePipelineCache", r))?;
        Ok(Self {
            device: device.raw().clone(),
            handle,
        })
    }

    /// Raw handle for use in `vkCreateComputePipelines`.
    pub fn handle(&self) -> vk::PipelineCache {
        self.handle
    }

    /// Serialise the cache to a fresh `Vec<u8>` for persistence.
    /// Empty caches still produce a small header only blob, never
    /// less than a handful of bytes.
    pub fn serialize(&self) -> Result<Vec<u8>> {
        // SAFETY: device and cache handles are alive.
        unsafe { self.device.get_pipeline_cache_data(self.handle) }
            .map_err(|r| vk_err("vkGetPipelineCacheData", r))
    }
}

impl Drop for PipelineCache {
    fn drop(&mut self) {
        // SAFETY: see VulkanDevice Drop comment for the ordering
        // invariant. PipelineCache always drops before the parent
        // device.
        unsafe { self.device.destroy_pipeline_cache(self.handle, None) };
    }
}