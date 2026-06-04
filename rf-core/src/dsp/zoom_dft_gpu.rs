//! GPU zoom DFT pipeline.
//!
//! Wraps the compute shader in shaders/zoom_dft.comp. One instance owns
//! its pipeline, descriptor set, and two host visible buffers: an input
//! buffer for the windowed complex samples and an output buffer for the
//! complex bin sums. The buffers are sized at construction for `max_n`
//! input samples and `max_bins` output bins and reused across calls.
//!
//! The shader produces only the raw complex bin sums. The window and the
//! dB conversion live in dsp::zoom_dft so the CPU and GPU backends share
//! them and differ only in where the bin sums are computed.
//!
//! Concurrency: `execute` blocks on a per-call fence. The spectrum
//! analyzer runs at a few hertz so the synchronous wait is acceptable.
//! The structure mirrors GpuFft and can adopt the same double-buffering
//! approach later if a higher call rate demands it.

use ash::vk;

use crate::dsp::zoom_dft::{bin_sums_cpu, finalize_db, hann_window};
use crate::error::{vk_err, Result, RfCoreError};
use crate::types::iq::Iq;
use crate::vulkan::{
    DescriptorPool, HostBuffer, MemoryAllocator, OneShotCmd, PipelineCache, ShaderModule,
    VulkanDevice,
};

const ZOOM_SPV: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/zoom_dft.spv"));

/// Workgroup x dimension declared in the shader. Must match.
const WG_SIZE: u32 = 64;

/// Push constant block. Mirrors the GLSL declaration byte for byte.
#[repr(C)]
#[derive(Copy, Clone)]
struct PushConstants {
    n: u32,
    bins: u32,
    fs: f32,
    span: f32,
}

const _: () = {
    assert!(core::mem::size_of::<PushConstants>() == 16);
    assert!(core::mem::align_of::<PushConstants>() == 4);
};

/// GPU zoom DFT pipeline plus its two backing buffers.
pub struct GpuZoomDft {
    // Drop order top to bottom. The command machinery drains the queue,
    // then the pipeline and layouts are destroyed, then the buffers free
    // themselves.
    cmd: OneShotCmd,
    pipeline: vk::Pipeline,
    pipeline_layout: vk::PipelineLayout,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
    in_buffer: HostBuffer,
    out_buffer: HostBuffer,
    device: ash::Device,
    max_n: usize,
    max_bins: usize,
}

impl GpuZoomDft {
    /// Build the pipeline and allocate the input and output buffers. The
    /// lower level Vulkan handles are passed directly rather than through
    /// RfCore so this can be constructed during RfCore assembly.
    pub fn new(
        device: &VulkanDevice,
        pipeline_cache: vk::PipelineCache,
        descriptor_pool: vk::DescriptorPool,
        max_n: usize,
        max_bins: usize,
    ) -> Result<Self> {
        if max_n == 0 || max_bins == 0 {
            return Err(RfCoreError::InvalidArgument("zoom dft buffer sizes are zero"));
        }

        let raw = device.raw();
        let shader = ShaderModule::from_spirv_bytes(device, ZOOM_SPV)?;

        // Two storage buffers: input samples at binding 0, output bins at
        // binding 1.
        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        let dsl_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings);
        // SAFETY: dsl_info pointers reference local stack data.
        let descriptor_set_layout =
            unsafe { raw.create_descriptor_set_layout(&dsl_info, None) }
                .map_err(|r| vk_err("vkCreateDescriptorSetLayout", r))?;

        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(core::mem::size_of::<PushConstants>() as u32);
        let layouts = [descriptor_set_layout];
        let pl_info = vk::PipelineLayoutCreateInfo::default()
            .set_layouts(&layouts)
            .push_constant_ranges(std::slice::from_ref(&push_range));
        // SAFETY: pl_info pointers reference local stack data.
        let pipeline_layout = unsafe { raw.create_pipeline_layout(&pl_info, None) }
            .map_err(|r| vk_err("vkCreatePipelineLayout", r))?;

        let entry = std::ffi::CString::new("main").expect("static literal");
        let stage = vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::COMPUTE)
            .module(shader.handle())
            .name(&entry);
        let cp_info = vk::ComputePipelineCreateInfo::default()
            .stage(stage)
            .layout(pipeline_layout);
        // SAFETY: cp_info pointers reference local stack data; the shader
        // module stays alive until pipeline creation returns.
        let pipelines =
            unsafe { raw.create_compute_pipelines(pipeline_cache, &[cp_info], None) }
                .map_err(|(_, r)| vk_err("vkCreateComputePipelines", r))?;
        let pipeline = pipelines[0];
        drop(shader);

        let allocator = MemoryAllocator::new(device);
        let in_bytes = (max_n * core::mem::size_of::<Iq>()) as vk::DeviceSize;
        let out_bytes = (max_bins * core::mem::size_of::<Iq>()) as vk::DeviceSize;
        let in_buffer =
            HostBuffer::new(device, &allocator, in_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;
        let out_buffer =
            HostBuffer::new(device, &allocator, out_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;

        let set_layouts = [descriptor_set_layout];
        let alloc_info = vk::DescriptorSetAllocateInfo::default()
            .descriptor_pool(descriptor_pool)
            .set_layouts(&set_layouts);
        // SAFETY: pool is alive for the lifetime of RfCore.
        let sets = unsafe { raw.allocate_descriptor_sets(&alloc_info) }
            .map_err(|r| vk_err("vkAllocateDescriptorSets", r))?;
        let descriptor_set = sets[0];

        let in_info = [vk::DescriptorBufferInfo::default()
            .buffer(in_buffer.handle())
            .offset(0)
            .range(in_bytes)];
        let out_info = [vk::DescriptorBufferInfo::default()
            .buffer(out_buffer.handle())
            .offset(0)
            .range(out_bytes)];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&in_info),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&out_info),
        ];
        // SAFETY: writes reference local stack data.
        unsafe { raw.update_descriptor_sets(&writes, &[]) };

        let cmd = OneShotCmd::new(device)?;

        Ok(Self {
            cmd,
            pipeline,
            pipeline_layout,
            descriptor_set_layout,
            descriptor_set,
            descriptor_pool,
            in_buffer,
            out_buffer,
            device: raw.clone(),
            max_n,
            max_bins,
        })
    }

    /// Compute the bin sums for `windowed` on the GPU. `windowed` holds
    /// the Hann-windowed complex samples. `out_sums` receives `bins`
    /// complex sums. Returns an error when the request exceeds the
    /// buffers; the caller falls back to the CPU path on error.
    pub fn execute(
        &mut self,
        windowed: &[Iq],
        span_hz: f32,
        fs_hz: f32,
        bins: usize,
        out_sums: &mut [Iq],
    ) -> Result<()> {
        let n = windowed.len();
        if n == 0 || n > self.max_n {
            return Err(RfCoreError::InvalidArgument("zoom dft n out of range"));
        }
        if bins == 0 || bins > self.max_bins || out_sums.len() < bins {
            return Err(RfCoreError::InvalidArgument("zoom dft bins out of range"));
        }

        copy_in(self.in_buffer.as_slice_mut(), windowed);

        let pc = PushConstants {
            n: n as u32,
            bins: bins as u32,
            fs: fs_hz,
            span: span_hz,
        };
        // SAFETY: PushConstants is repr(C) with size 16 and no padding.
        let pc_bytes = unsafe {
            std::slice::from_raw_parts(
                (&pc as *const PushConstants) as *const u8,
                core::mem::size_of::<PushConstants>(),
            )
        };

        let pipeline = self.pipeline;
        let pipeline_layout = self.pipeline_layout;
        let descriptor_set = self.descriptor_set;
        let groups = (bins as u32).div_ceil(WG_SIZE);

        self.cmd.record_and_submit(|dev, cb| {
            // SAFETY: cb is recording by the caller's contract.
            unsafe {
                dev.cmd_bind_pipeline(cb, vk::PipelineBindPoint::COMPUTE, pipeline);
                dev.cmd_bind_descriptor_sets(
                    cb,
                    vk::PipelineBindPoint::COMPUTE,
                    pipeline_layout,
                    0,
                    &[descriptor_set],
                    &[],
                );
                dev.cmd_push_constants(
                    cb,
                    pipeline_layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    pc_bytes,
                );
                dev.cmd_dispatch(cb, groups, 1, 1);

                let mem_barrier = vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::HOST_READ);
                dev.cmd_pipeline_barrier(
                    cb,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::HOST,
                    vk::DependencyFlags::empty(),
                    &[mem_barrier],
                    &[],
                    &[],
                );
            }
            Ok(())
        })?;

        copy_out(self.out_buffer.as_slice(), &mut out_sums[..bins]);
        Ok(())
    }
}

impl Drop for GpuZoomDft {
    fn drop(&mut self) {
        // SAFETY: by field order the command machinery has already drained
        // the queue. The set is returned to the pool, then the pipeline
        // and layouts are destroyed while the device is still alive.
        unsafe {
            let _ = self
                .device
                .free_descriptor_sets(self.descriptor_pool, &[self.descriptor_set]);
            self.device.destroy_pipeline(self.pipeline, None);
            self.device.destroy_pipeline_layout(self.pipeline_layout, None);
            self.device
                .destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        }
    }
}

fn copy_in(dst: &mut [u8], src: &[Iq]) {
    let len = src.len() * core::mem::size_of::<Iq>();
    debug_assert!(dst.len() >= len);
    // SAFETY: Iq is repr(C) size 8 align 4; dst is host visible and is at
    // least len bytes, exclusively owned through &mut at the call site.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr() as *const u8, dst.as_mut_ptr(), len);
    }
}

fn copy_out(src: &[u8], dst: &mut [Iq]) {
    let len = dst.len() * core::mem::size_of::<Iq>();
    debug_assert!(src.len() >= len);
    // SAFETY: source is host coherent so device writes are visible; sizes
    // are checked above.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr(), dst.as_mut_ptr() as *mut u8, len);
    }
}

/// CPU reference path. Exposed so the dispatcher can call it without going
/// through a GpuZoomDft when GPU is disabled or unavailable.
pub fn zoom_dft_cpu(
    iq: &[Iq],
    hann: &[f32],
    n: usize,
    span_hz: f32,
    fs_hz: f32,
    bins: usize,
    out_sums: &mut [Iq],
) {
    bin_sums_cpu(iq, hann, n, span_hz, fs_hz, bins, out_sums);
}

// Re-export so callers needing the finalize step alongside the GPU path
// can reach it from one module.
pub use crate::dsp::zoom_dft::{finalize_db as zoom_finalize_db, hann_window as zoom_hann_window};