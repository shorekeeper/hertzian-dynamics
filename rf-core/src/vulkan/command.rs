//! One shot command pool, command buffer and fence.
//!
//! The DSP path runs many short compute submissions. Each `record_and_submit`
//! resets the command buffer, records the caller supplied commands,
//! submits with a fence, and waits. The wrapper exists so individual
//! DSP modules do not each reinvent the pool plus fence dance.

use ash::vk;

use crate::error::{vk_err, Result};
use crate::vulkan::device::VulkanDevice;

/// Single command buffer plus its fence, sized for short submissions.
pub struct OneShotCmd {
    device: ash::Device,
    queue: vk::Queue,
    pool: vk::CommandPool,
    buffer: vk::CommandBuffer,
    fence: vk::Fence,
}

impl OneShotCmd {
    /// Create the pool, allocate one primary buffer, create a fence
    /// in the signaled state so the first wait is a no op.
    pub fn new(device: &VulkanDevice) -> Result<Self> {
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(device.families().compute)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        // SAFETY: pointers reference local stack data.
        let pool = unsafe { device.raw().create_command_pool(&pool_info, None) }
            .map_err(|r| vk_err("vkCreateCommandPool", r))?;

        let alloc_info = vk::CommandBufferAllocateInfo::default()
            .command_pool(pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        // SAFETY: alloc_info pointers reference local stack data.
        let buffers = unsafe { device.raw().allocate_command_buffers(&alloc_info) }
            .map_err(|r| vk_err("vkAllocateCommandBuffers", r))?;
        let buffer = buffers[0];

        let fence_info =
            vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
        // SAFETY: fence_info pointers reference local stack data.
        let fence = unsafe { device.raw().create_fence(&fence_info, None) }
            .map_err(|r| vk_err("vkCreateFence", r))?;

        Ok(Self {
            device: device.raw().clone(),
            queue: device.compute_queue(),
            pool,
            buffer,
            fence,
        })
    }

    /// Record `record` into the buffer and submit synchronously.
    /// Blocks until the GPU finishes. Returns once the fence is
    /// signaled and the buffer is safe to record into again.
    ///
    /// `record` receives the device clone and the command buffer
    /// handle to issue Vulkan calls against. Any error returned by
    /// `record` aborts before submission.
    pub fn record_and_submit(
        &mut self,
        record: impl FnOnce(&ash::Device, vk::CommandBuffer) -> Result<()>,
    ) -> Result<()> {
        // SAFETY: fence is owned, never associated with any other
        // queue submission than the ones we issue below.
        unsafe {
            self.device
                .wait_for_fences(&[self.fence], true, u64::MAX)
                .map_err(|r| vk_err("vkWaitForFences", r))?;
            self.device
                .reset_fences(&[self.fence])
                .map_err(|r| vk_err("vkResetFences", r))?;
            self.device
                .reset_command_buffer(self.buffer, vk::CommandBufferResetFlags::empty())
                .map_err(|r| vk_err("vkResetCommandBuffer", r))?;
        }

        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        // SAFETY: command buffer is in initial state after the reset.
        unsafe {
            self.device
                .begin_command_buffer(self.buffer, &begin)
                .map_err(|r| vk_err("vkBeginCommandBuffer", r))?;
        }

        record(&self.device, self.buffer)?;

        // SAFETY: buffer is in the recording state.
        unsafe {
            self.device
                .end_command_buffer(self.buffer)
                .map_err(|r| vk_err("vkEndCommandBuffer", r))?;
        }

        let cmd_bufs = [self.buffer];
        let submit_info = vk::SubmitInfo::default().command_buffers(&cmd_bufs);
        // SAFETY: submit_info pointers alive for the duration of the call.
        unsafe {
            self.device
                .queue_submit(self.queue, &[submit_info], self.fence)
                .map_err(|r| vk_err("vkQueueSubmit", r))?;
            self.device
                .wait_for_fences(&[self.fence], true, u64::MAX)
                .map_err(|r| vk_err("vkWaitForFences", r))?;
        }
        Ok(())
    }
}

impl Drop for OneShotCmd {
    fn drop(&mut self) {
        // SAFETY: parent device outlives this struct by field order.
        unsafe {
            let _ = self.device.wait_for_fences(&[self.fence], true, u64::MAX);
            self.device.destroy_fence(self.fence, None);
            self.device.destroy_command_pool(self.pool, None);
        }
    }
}