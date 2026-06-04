//! Physical device selection and logical device creation.
//!
//! rf-core is compute only. We need one queue from a family that
//! supports `VK_QUEUE_COMPUTE_BIT`. Among candidate devices we score
//! discrete GPUs over integrated ones, prefer a dedicated compute
//! queue family (one without `VK_QUEUE_GRAPHICS_BIT`) to dodge
//! contention with the display path, and add a small bonus for
//! larger `maxStorageBufferRange`.

use ash::vk;

use crate::error::{vk_err, Result, RfCoreError};
use crate::vulkan::instance::VulkanInstance;

/// Indices of queue families this engine actually uses.
#[derive(Copy, Clone, Debug)]
pub struct QueueFamilyIndices {
    /// Index of the queue family used for all compute work.
    pub compute: u32,
}

/// Owning Vulkan logical device plus the compute queue handle.
///
/// Sub objects (memory allocations, pipeline cache, descriptor pool)
/// hold their own clone of `ash::Device` so they can call destroy
/// functions during their own Drop without borrowing back into this
/// struct. The `vk::Device` they all point at stays alive until this
/// wrapper itself is dropped.
pub struct VulkanDevice {
    physical: vk::PhysicalDevice,
    device: ash::Device,
    compute_queue: vk::Queue,
    families: QueueFamilyIndices,
    properties: vk::PhysicalDeviceProperties,
    memory_props: vk::PhysicalDeviceMemoryProperties,
}

impl VulkanDevice {
    /// Pick the best physical device and create a logical device.
    ///
    /// `require_float64` toggles `shaderFloat64`. Most discrete GPUs
    /// support it but throughput is typically 1/32 of f32, so the DSP
    /// kernels added later avoid f64 on the hot path. The flag is
    /// kept for diagnostic kernels and for future scientific work.
    pub fn pick(instance: &VulkanInstance, require_float64: bool) -> Result<Self> {
        let candidates = instance.physical_devices()?;
        if candidates.is_empty() {
            return Err(RfCoreError::NoSuitableDevice);
        }

        let raw_instance = instance.raw();
        let mut best: Option<(i64, vk::PhysicalDevice, u32)> = None;

        for pd in candidates {
            // SAFETY: pd is a handle returned by the loader and
            // remains valid as long as the instance is alive.
            let props = unsafe { raw_instance.get_physical_device_properties(pd) };
            let features = unsafe { raw_instance.get_physical_device_features(pd) };
            let families = unsafe {
                raw_instance.get_physical_device_queue_family_properties(pd)
            };

            if require_float64 && features.shader_float64 == 0 {
                continue;
            }

            let compute_idx = families.iter().position(|q| {
                q.queue_flags.contains(vk::QueueFlags::COMPUTE) && q.queue_count > 0
            });
            let Some(idx) = compute_idx else { continue };

            let mut score: i64 = 0;
            if props.device_type == vk::PhysicalDeviceType::DISCRETE_GPU {
                score += 1000;
            } else if props.device_type == vk::PhysicalDeviceType::INTEGRATED_GPU {
                score += 100;
            }
            if !families[idx].queue_flags.contains(vk::QueueFlags::GRAPHICS) {
                // Async compute family on discrete cards. Less likely
                // to be starved by the desktop compositor.
                score += 100;
            }
            score += (props.limits.max_storage_buffer_range as i64) / 1_000_000;

            match best {
                None => best = Some((score, pd, idx as u32)),
                Some((cur, _, _)) if score > cur => best = Some((score, pd, idx as u32)),
                _ => {}
            }
        }

        let Some((_, physical, compute_family)) = best else {
            return Err(RfCoreError::NoSuitableDevice);
        };

        let priorities = [1.0f32];
        let queue_create = [vk::DeviceQueueCreateInfo::default()
            .queue_family_index(compute_family)
            .queue_priorities(&priorities)];

        let mut features = vk::PhysicalDeviceFeatures::default();
        if require_float64 {
            features = features.shader_float64(true);
        }

        let device_create = vk::DeviceCreateInfo::default()
            .queue_create_infos(&queue_create)
            .enabled_features(&features);

        // SAFETY: every pointer in device_create references local
        // stack data alive for the duration of the call.
        let device = unsafe {
            raw_instance.create_device(physical, &device_create, None)
        }
        .map_err(|r| vk_err("vkCreateDevice", r))?;

        // SAFETY: compute_family is a family index that the create
        // call above reserved a single queue for at index 0.
        let compute_queue = unsafe { device.get_device_queue(compute_family, 0) };

        let properties = unsafe { raw_instance.get_physical_device_properties(physical) };
        let memory_props = unsafe { raw_instance.get_physical_device_memory_properties(physical) };

        Ok(Self {
            physical,
            device,
            compute_queue,
            families: QueueFamilyIndices { compute: compute_family },
            properties,
            memory_props,
        })
    }

    /// Borrow the raw `ash::Device` for sub modules. Sub objects
    /// clone this handle into their own state.
    pub(crate) fn raw(&self) -> &ash::Device {
        &self.device
    }

    /// Physical device handle. Stable for the lifetime of `self`.
    pub fn physical(&self) -> vk::PhysicalDevice {
        self.physical
    }

    /// The single compute queue handle.
    pub fn compute_queue(&self) -> vk::Queue {
        self.compute_queue
    }

    /// Indices of the queue families in use.
    pub fn families(&self) -> QueueFamilyIndices {
        self.families
    }

    /// Driver reported physical device properties (limits, name).
    pub fn properties(&self) -> &vk::PhysicalDeviceProperties {
        &self.properties
    }

    /// Driver reported memory heap layout.
    pub fn memory_properties(&self) -> &vk::PhysicalDeviceMemoryProperties {
        &self.memory_props
    }

    /// Block until the device is idle. Used during shutdown and in
    /// tests to make sure no command buffer is in flight when we
    /// destroy dependent resources.
    pub fn wait_idle(&self) -> Result<()> {
        // SAFETY: device handle is alive.
        unsafe { self.device.device_wait_idle() }
            .map_err(|r| vk_err("vkDeviceWaitIdle", r))
    }
}

impl Drop for VulkanDevice {
    fn drop(&mut self) {
        // We are in Drop. By the time we get here every
        // sub object that previously held a clone of this device
        // has already been dropped (drop order is field order in
        // the parent struct, by Rust rules).
        unsafe {
            let _ = self.device.device_wait_idle();
            self.device.destroy_device(None);
        }
    }
}