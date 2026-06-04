//! Shader module wrapper.
//!
//! Shaders are pre compiled SPIR-V blobs.

use ash::vk;

use crate::error::{vk_err, Result, RfCoreError};
use crate::vulkan::device::VulkanDevice;

/// Owning Vulkan shader module. Destroys on Drop.
pub struct ShaderModule {
    device: ash::Device,
    handle: vk::ShaderModule,
}

impl ShaderModule {
    /// Create a shader module from raw SPIR-V bytes.
    ///
    /// SPIR-V is a stream of 32 bit little endian words, so the byte
    /// length must be a multiple of four. We rebuild the word slice
    /// from bytes through a temporary `Vec<u32>` to side step the
    /// alignment issue introduced by `include_bytes!`, which on some
    /// Rust versions returns a `&[u8; N]` with only one byte of
    /// alignment.
    pub fn from_spirv_bytes(device: &VulkanDevice, bytes: &[u8]) -> Result<Self> {
        if bytes.is_empty() {
            return Err(RfCoreError::InvalidShader("empty SPIR-V module"));
        }
        if bytes.len() % 4 != 0 {
            return Err(RfCoreError::InvalidShader(
                "SPIR-V byte length not divisible by 4",
            ));
        }
        let mut words: Vec<u32> = Vec::with_capacity(bytes.len() / 4);
        for chunk in bytes.chunks_exact(4) {
            words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
        }
        // The first word of a valid SPIR-V module is the magic number
        // 0x07230203. Reject anything else early with a clear error.
        const SPIRV_MAGIC: u32 = 0x0723_0203;
        if words[0] != SPIRV_MAGIC {
            return Err(RfCoreError::InvalidShader("SPIR-V magic number mismatch"));
        }
        let info = vk::ShaderModuleCreateInfo::default().code(&words);
        // SAFETY: info pointers reference `words` which is alive for
        // the duration of this call.
        let handle = unsafe { device.raw().create_shader_module(&info, None) }
            .map_err(|r| vk_err("vkCreateShaderModule", r))?;
        Ok(Self {
            device: device.raw().clone(),
            handle,
        })
    }

    /// Raw handle for use in `vkCreateComputePipelines`.
    pub fn handle(&self) -> vk::ShaderModule {
        self.handle
    }
}

impl Drop for ShaderModule {
    fn drop(&mut self) {
        // SAFETY: declared before VulkanDevice in any container that
        // owns both. Sub objects drop before their parent device.
        unsafe { self.device.destroy_shader_module(self.handle, None) };
    }
}