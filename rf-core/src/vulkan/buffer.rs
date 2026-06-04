//! Buffer wrapper: buffer + memory + persistent mapping.

use ash::vk;

use crate::error::{vk_err, Result};
use crate::vulkan::device::VulkanDevice;
use crate::vulkan::memory::{DeviceMemory, MemoryAllocator, MemoryUsage};

/// Host visible coherent storage buffer with a persistent CPU
/// mapping. Drop frees and unmaps in the correct order.
pub struct HostBuffer {
    // Drop order: unmap and destroy buffer before memory frees.
    device: ash::Device,
    buffer: vk::Buffer,
    memory: DeviceMemory,
    mapped: *mut u8,
    size: vk::DeviceSize,
}

// The raw pointer prevents an automatic Send/Sync derivation. The
// pointer is logically owned by the buffer (one mapping, one owner)
// and the "JNI layer" will guarantee single threaded
// access from Java. We mark it Send so the higher level can move
// buffers between worker threads under its own lock; not Sync.
unsafe impl Send for HostBuffer {}

impl HostBuffer {
    /// Allocate a host coherent buffer of `size` bytes with the
    /// requested usage flags. The memory is mapped for the lifetime
    /// of the buffer; the function returns once writes through
    /// `as_slice_mut` become visible to the device without a flush.
    pub fn new(
        device: &VulkanDevice,
        allocator: &MemoryAllocator<'_>,
        size: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
    ) -> Result<Self> {
        debug_assert!(size > 0);
        let create_info = vk::BufferCreateInfo::default()
            .size(size)
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);

        // SAFETY: create_info pointers reference local stack data.
        let buffer = unsafe { device.raw().create_buffer(&create_info, None) }
            .map_err(|r| vk_err("vkCreateBuffer", r))?;

        // SAFETY: buffer is valid.
        let reqs = unsafe { device.raw().get_buffer_memory_requirements(buffer) };
        let memory = allocator.allocate(reqs.size, reqs.memory_type_bits, MemoryUsage::HostVisible)?;

        // SAFETY: buffer and memory are both alive and belong to this
        // device. Offset zero is valid since we allocated specifically
        // for this buffer.
        unsafe { device.raw().bind_buffer_memory(buffer, memory.handle(), 0) }
            .map_err(|r| vk_err("vkBindBufferMemory", r))?;

        // SAFETY: memory is host visible per the MemoryUsage choice.
        let mapped = unsafe {
            device.raw().map_memory(
                memory.handle(),
                0,
                vk::WHOLE_SIZE,
                vk::MemoryMapFlags::empty(),
            )
        }
        .map_err(|r| vk_err("vkMapMemory", r))? as *mut u8;

        Ok(Self {
            device: device.raw().clone(),
            buffer,
            memory,
            mapped,
            size,
        })
    }

    /// Raw buffer handle for descriptor writes and barriers.
    pub fn handle(&self) -> vk::Buffer {
        self.buffer
    }

    /// Allocation size in bytes.
    pub fn size(&self) -> vk::DeviceSize {
        self.size
    }

    /// Borrow the persistent mapping as a mutable byte slice. Coherent
    /// memory means writes are visible to the device without a flush;
    /// the caller is responsible for synchronising with any in flight
    /// shader access via fences or semaphores.
    pub fn as_slice_mut(&mut self) -> &mut [u8] {
        // SAFETY: the pointer is alive for the lifetime of self, the
        // mapping covers `size` bytes, and the &mut self borrow
        // guarantees no aliasing.
        unsafe { std::slice::from_raw_parts_mut(self.mapped, self.size as usize) }
    }

    /// Borrow the persistent mapping as a shared byte slice.
    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: same as as_slice_mut, with the &self borrow.
        unsafe { std::slice::from_raw_parts(self.mapped, self.size as usize) }
    }
}

impl Drop for HostBuffer {
    fn drop(&mut self) {
        // SAFETY: handles are alive by the field ownership rules;
        // memory drops after this block, freeing the allocation.
        unsafe {
            self.device.unmap_memory(self.memory.handle());
            self.device.destroy_buffer(self.buffer, None);
        }
    }
}