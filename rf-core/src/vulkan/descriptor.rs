//! Descriptor pool.
//!
//! Compute pipelines bind storage and uniform buffers through
//! descriptor sets carved out of this pool. The pool is sized once
//! at startup with conservative caps.

use ash::vk;

use crate::error::{vk_err, Result, RfCoreError};
use crate::vulkan::device::VulkanDevice;

/// Cap configuration for the descriptor pool. Defaults are sized for
/// the first few compute kernels (FFT, FIR, IIR, demodulators) plus
/// headroom for diagnostics. Adjust upward once propagation kernels
/// land in code.
#[derive(Copy, Clone, Debug)]
pub struct DescriptorPoolCapacity {
    /// Maximum number of descriptor sets that may be live at once.
    pub max_sets: u32,
    /// Maximum total number of storage buffer bindings across all
    /// live sets.
    pub max_storage_buffers: u32,
    /// Maximum total number of uniform buffer bindings.
    pub max_uniform_buffers: u32,
}

impl Default for DescriptorPoolCapacity {
    fn default() -> Self {
        Self {
            max_sets: 256,
            max_storage_buffers: 1024,
            max_uniform_buffers: 256,
        }
    }
}

/// Owning Vulkan descriptor pool. Destroys on Drop.
pub struct DescriptorPool {
    device: ash::Device,
    handle: vk::DescriptorPool,
    capacity: DescriptorPoolCapacity,
}

impl DescriptorPool {
    /// Create the pool with the requested capacity.
    pub fn new(device: &VulkanDevice, capacity: DescriptorPoolCapacity) -> Result<Self> {
        if capacity.max_sets == 0 {
            return Err(RfCoreError::InvalidArgument("descriptor pool max_sets is zero"));
        }
        let sizes = [
            vk::DescriptorPoolSize {
                ty: vk::DescriptorType::STORAGE_BUFFER,
                descriptor_count: capacity.max_storage_buffers,
            },
            vk::DescriptorPoolSize {
                ty: vk::DescriptorType::UNIFORM_BUFFER,
                descriptor_count: capacity.max_uniform_buffers,
            },
        ];
        let info = vk::DescriptorPoolCreateInfo::default()
            .max_sets(capacity.max_sets)
            .pool_sizes(&sizes)
            .flags(vk::DescriptorPoolCreateFlags::FREE_DESCRIPTOR_SET);
        // SAFETY: info pointers are alive for the duration of the call.
        let handle = unsafe { device.raw().create_descriptor_pool(&info, None) }
            .map_err(|r| vk_err("vkCreateDescriptorPool", r))?;
        Ok(Self {
            device: device.raw().clone(),
            handle,
            capacity,
        })
    }

    /// Raw handle for use in `vkAllocateDescriptorSets`.
    pub fn handle(&self) -> vk::DescriptorPool {
        self.handle
    }

    /// Cap configuration the pool was created with.
    pub fn capacity(&self) -> DescriptorPoolCapacity {
        self.capacity
    }
}

impl Drop for DescriptorPool {
    fn drop(&mut self) {
        // SAFETY: declared before VulkanDevice in RfCore, drops first.
        unsafe { self.device.destroy_descriptor_pool(self.handle, None) };
    }
}