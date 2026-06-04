//! Vulkan plumbing.
//!
//! Everything Vulkan related is locked behind this module. The rest
//! of the crate refers to Vulkan only through the wrappers re
//! exported below. If we ever want to swap in a different backend
//! or run on a stub for tests, this module is the single seam.
//!
//! Lifecycle and drop order
//! ------------------------
//!
//! Each wrapper owns the Vulkan handle it created and destroys it in
//! its `Drop`. Sub objects (pipeline cache, descriptor pool, shader
//! module, device memory) keep their own clone of `ash::Device`,
//! which is cheap because `ash::Device` is itself a thin wrapper
//! around a function pointer table. Drop order is the declaration
//! order in the parent struct, so the rule is: declare children
//! before parents inside [`crate::context::RfCore`].
//!
//! Nothing in this module knows anything about RF, DSP or Minecraft.
//! It is pure plumbing.

pub mod instance;
pub mod device;
pub mod memory;
pub mod pipeline;
pub mod descriptor;
pub mod shader;
pub mod buffer;
pub mod command;

pub use buffer::HostBuffer;
pub use command::OneShotCmd;
pub use descriptor::{DescriptorPool, DescriptorPoolCapacity};
pub use device::{QueueFamilyIndices, VulkanDevice};
pub use instance::VulkanInstance;
pub use memory::{DeviceMemory, MemoryAllocator, MemoryUsage};
pub use pipeline::PipelineCache;
pub use shader::ShaderModule;