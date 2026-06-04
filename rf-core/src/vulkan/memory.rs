//! Device memory allocations.
//!

use ash::vk;

use crate::error::{vk_err, Result, RfCoreError};
use crate::vulkan::device::VulkanDevice;

/// High level memory usage hint. Translated to Vulkan property flags
/// at allocation time so callers do not have to import `ash::vk`.
#[derive(Copy, Clone, Debug)]
pub enum MemoryUsage {
    /// Resides on the GPU, invisible to the host. Fastest path for
    /// shader storage. Use for long lived read mostly data such as
    /// material attenuation tables.
    DeviceLocal,
    /// Host visible and coherent. Writes from the CPU appear on the
    /// GPU without an explicit flush. Use for per frame upload buffers
    /// such as emission descriptors.
    HostVisible,
    /// Host visible and cached. Cheap to read back on the CPU at the
    /// cost of an explicit invalidate after the GPU writes. Use for
    /// readback buffers such as completed spectrum chunks.
    HostCached,
}

/// Owning device memory allocation. Frees on Drop.
///
/// The wrapper keeps its own clone of `ash::Device` because the
/// alternative (a borrow with a lifetime) propagates lifetimes into
/// every struct that stores an allocation, which becomes painful
/// once allocations live inside long lived pipeline state.
pub struct DeviceMemory {
    device: ash::Device,
    handle: vk::DeviceMemory,
    size: vk::DeviceSize,
}

impl DeviceMemory {
    /// Raw handle for use in `vkBindBufferMemory` and friends.
    pub fn handle(&self) -> vk::DeviceMemory {
        self.handle
    }

    /// Size of the allocation, in bytes.
    pub fn size(&self) -> vk::DeviceSize {
        self.size
    }
}

impl Drop for DeviceMemory {
    fn drop(&mut self) {
        // SAFETY: the underlying vk::Device is alive because field
        // drop order in RfCore guarantees memory drops before device.
        unsafe { self.device.free_memory(self.handle, None) };
    }
}

/// Stateless allocator over a borrowed device.
pub struct MemoryAllocator<'a> {
    device: &'a VulkanDevice,
}

impl<'a> MemoryAllocator<'a> {
    /// Construct an allocator that allocates from `device`.
    pub fn new(device: &'a VulkanDevice) -> Self {
        Self { device }
    }

    /// Allocate `size` bytes of memory matching both the bit mask
    /// reported by a buffer or image memory requirements query and
    /// the usage hint. Fails with `NoMemoryType` if no Vulkan memory
    /// type satisfies both constraints.
    pub fn allocate(
        &self,
        size: vk::DeviceSize,
        type_bits: u32,
        usage: MemoryUsage,
    ) -> Result<DeviceMemory> {
        if size == 0 {
            return Err(RfCoreError::InvalidArgument("zero sized allocation"));
        }

        let needed = match usage {
            MemoryUsage::DeviceLocal => vk::MemoryPropertyFlags::DEVICE_LOCAL,
            MemoryUsage::HostVisible => {
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT
            }
            MemoryUsage::HostCached => {
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_CACHED
            }
        };

        let type_index = self.find_memory_type(type_bits, needed)?;
        let info = vk::MemoryAllocateInfo::default()
            .allocation_size(size)
            .memory_type_index(type_index);

        // SAFETY: info pointers reference local stack data alive for
        // the duration of the call.
        let handle = unsafe { self.device.raw().allocate_memory(&info, None) }
            .map_err(|r| vk_err("vkAllocateMemory", r))?;

        Ok(DeviceMemory {
            device: self.device.raw().clone(),
            handle,
            size,
        })
    }

    fn find_memory_type(
        &self,
        type_bits: u32,
        properties: vk::MemoryPropertyFlags,
    ) -> Result<u32> {
        let mem = self.device.memory_properties();
        for i in 0..mem.memory_type_count {
            let bit = 1u32 << i;
            if (type_bits & bit) != 0
                && mem.memory_types[i as usize]
                    .property_flags
                    .contains(properties)
            {
                return Ok(i);
            }
        }
        Err(RfCoreError::NoMemoryType)
    }
}