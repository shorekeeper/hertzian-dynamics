//! GPU FFT pipeline.
//!
//! Wraps the Cooley-Tukey radix-2 compute shader in `shaders/fft_radix2.comp`.
//! Each `GpuFft` instance owns the descriptor set layout, pipeline
//! and a single host visible buffer sized for `max_n` complex
//! samples. The buffer is reused across calls; only the contents
//! change.
//!
//! Concurrency
//! -----------
//!
//! `execute` is `&mut self`, the buffer is single owner, and the
//! internal `OneShotCmd` blocks on a fence per call. Code should
//! introduce per receiver `GpuFft` instances on worker threads so the
//! synchronous wait stops being a bottleneck; the public API does
//! not need to change for that.
//!
//! Numerical contract
//! ------------------
//!
//! The shader is bit identical in formulation to `dsp::fft::fft`. Max
//! absolute error against the CPU reference is around 1e-4 for an
//! N = 4096 transform of unit magnitude random input. The
//! cross-validation example in `examples/gpu_fft_validate.rs`
//! reports the figure on stdout.

use ash::vk;

use crate::dsp::fft::FftDirection;
use crate::error::{vk_err, Result, RfCoreError};
use crate::types::iq::Iq;
use crate::vulkan::{
    HostBuffer, MemoryAllocator, OneShotCmd, VulkanDevice,
};

// Precompiled SPIR-V produced by build.rs from shaders/fft_radix2.comp.
const FFT_SPV: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fft_radix2.spv"));

/// Workgroup x dimension declared in the shader. Must match.
const WG_SIZE: u32 = 256;

/// Minimum FFT length the kernel supports. Below this the
/// `per_thread = max(1, half_n / WG_SIZE)` clamp on the GPU side
/// stops being meaningful because workgroup invocations would idle.
/// We forward small FFTs to the CPU implementation at a higher
/// layer; here we reject them outright.
pub const GPU_FFT_MIN_N: usize = 512;

/// Maximum FFT length, matched against the shader MAX_N.
pub const GPU_FFT_MAX_N: usize = 4096;

/// Push constant block. Mirrors the GLSL declaration in
/// shaders/fft_radix2.comp byte for byte.
#[repr(C)]
#[derive(Copy, Clone, Debug)]
struct PushConstants {
    n: u32,
    log_n: u32,
    dir_sign: f32,
    inv_scale: f32,
}

const _: () = {
    assert!(core::mem::size_of::<PushConstants>() == 16);
    assert!(core::mem::align_of::<PushConstants>() == 4);
};

/// FFT pipeline plus the I/O buffer that backs all transforms.
pub struct GpuFft {
    // Drop order top to bottom: descriptor set is freed when the
    // pool is destroyed (we do not free it explicitly because the
    // pool outlives the GpuFft). Pipeline, layout, set layout get
    // destroyed by their parent device. The OneShotCmd and the
    // HostBuffer free themselves on Drop. Order matters: cmd before
    // pipeline (no submissions outstanding), buffer last.
    cmd: OneShotCmd,
    pipeline: vk::Pipeline,
    pipeline_layout: vk::PipelineLayout,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
    buffer: HostBuffer,
    device: ash::Device,
    max_n: usize,
}

impl GpuFft {
    /// Build the pipeline and allocate a buffer big enough for
    /// `max_n` complex samples. `max_n` must be a power of two in
    /// `[GPU_FFT_MIN_N, GPU_FFT_MAX_N]`. Storage is host visible
    /// coherent so subsequent calls only update the contents.
    pub fn new(core: &crate::RfCore, max_n: usize) -> Result<Self> {
        if max_n < GPU_FFT_MIN_N || max_n > GPU_FFT_MAX_N {
            return Err(RfCoreError::InvalidArgument(
                "max_n outside [GPU_FFT_MIN_N, GPU_FFT_MAX_N]",
            ));
        }
        if !max_n.is_power_of_two() {
            return Err(RfCoreError::InvalidArgument("max_n must be a power of two"));
        }

        let device = core.device();
        let raw = device.raw();

        // 1. Shader module from the embedded SPIR-V.
        let shader = crate::vulkan::ShaderModule::from_spirv_bytes(device, FFT_SPV)?;

        // 2. Descriptor set layout: one storage buffer at binding 0.
        let bindings = [vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1)
            .stage_flags(vk::ShaderStageFlags::COMPUTE)];
        let dsl_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings);
        // SAFETY: dsl_info pointers reference local stack data.
        let descriptor_set_layout =
            unsafe { raw.create_descriptor_set_layout(&dsl_info, None) }
                .map_err(|r| vk_err("vkCreateDescriptorSetLayout", r))?;

        // 3. Pipeline layout: one descriptor set + push constants.
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

        // 4. Compute pipeline.
        let entry = std::ffi::CString::new("main").expect("static literal");
        let stage = vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::COMPUTE)
            .module(shader.handle())
            .name(&entry);
        let cp_info = vk::ComputePipelineCreateInfo::default()
            .stage(stage)
            .layout(pipeline_layout);
        // SAFETY: cp_info pointers reference local stack data; the
        // shader module stays alive until the end of this function
        // because we hold it by value until pipeline creation
        // returns success.
        let pipelines = unsafe {
            raw.create_compute_pipelines(core.pipeline_cache().handle(), &[cp_info], None)
        }
        .map_err(|(_, r)| vk_err("vkCreateComputePipelines", r))?;
        let pipeline = pipelines[0];
        drop(shader); // shader module no longer needed once the pipeline is created.

        // 5. Allocate the storage buffer (host visible coherent).
        let allocator = MemoryAllocator::new(device);
        let bytes = (max_n * core::mem::size_of::<Iq>()) as vk::DeviceSize;
        let buffer = HostBuffer::new(
            device,
            &allocator,
            bytes,
            vk::BufferUsageFlags::STORAGE_BUFFER,
        )?;

        // 6. Allocate the descriptor set from the global pool and
        // write the buffer into it. The write is permanent for the
        // lifetime of the buffer.
        let descriptor_pool = core.descriptor_pool().handle();
        let set_layouts = [descriptor_set_layout];
        let alloc_info = vk::DescriptorSetAllocateInfo::default()
            .descriptor_pool(descriptor_pool)
            .set_layouts(&set_layouts);
        // SAFETY: pool is alive for the lifetime of RfCore.
        let sets = unsafe { raw.allocate_descriptor_sets(&alloc_info) }
            .map_err(|r| vk_err("vkAllocateDescriptorSets", r))?;
        let descriptor_set = sets[0];

        let buf_info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.handle())
            .offset(0)
            .range(bytes)];
        let write = vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .buffer_info(&buf_info);
        // SAFETY: write references local stack data.
        unsafe { raw.update_descriptor_sets(&[write], &[]) };

        // 7. Command machinery.
        let cmd = OneShotCmd::new(device)?;

        Ok(Self {
            cmd,
            pipeline,
            pipeline_layout,
            descriptor_set_layout,
            descriptor_set,
            descriptor_pool,
            buffer,
            device: raw.clone(),
            max_n,
        })
    }

    /// Upper bound on `data.len()` accepted by `execute`.
    pub fn max_n(&self) -> usize {
        self.max_n
    }

    /// Transform `data` in place. `data.len()` must be a power of
    /// two in `[GPU_FFT_MIN_N, max_n()]`. Forward and inverse share
    /// the same kernel, parameterised by sign and scale through push
    /// constants. The function blocks until the GPU has retired the
    /// dispatch.
    pub fn execute(&mut self, data: &mut [Iq], direction: FftDirection) -> Result<()> {
        let n = data.len();
        if n < GPU_FFT_MIN_N || n > self.max_n {
            return Err(RfCoreError::InvalidArgument(
                "FFT length outside the supported range",
            ));
        }
        if !n.is_power_of_two() {
            return Err(RfCoreError::InvalidArgument("FFT length not a power of two"));
        }

        // Stage input into the mapped buffer. f32 alignment is
        // guaranteed by HostBuffer (Vulkan host visible memory is at
        // least 4 byte aligned for f32 storage buffers in std430).
        let bytes_in = bytemuck_like_copy_in(self.buffer.as_slice_mut(), data);
        debug_assert_eq!(bytes_in, n * core::mem::size_of::<Iq>());

        // Push constants.
        let pc = PushConstants {
            n: n as u32,
            log_n: n.trailing_zeros(),
            dir_sign: match direction {
                FftDirection::Forward => -1.0,
                FftDirection::Inverse => 1.0,
            },
            inv_scale: match direction {
                FftDirection::Forward => 1.0,
                FftDirection::Inverse => 1.0 / n as f32,
            },
        };
        // SAFETY: PushConstants is repr(C) with size 16 and no
        // padding bytes that would expose uninitialised memory.
        let pc_bytes = unsafe {
            std::slice::from_raw_parts(
                (&pc as *const PushConstants) as *const u8,
                core::mem::size_of::<PushConstants>(),
            )
        };

        let pipeline = self.pipeline;
        let pipeline_layout = self.pipeline_layout;
        let descriptor_set = self.descriptor_set;

        self.cmd.record_and_submit(|dev, cb| {
            // SAFETY: cb is in the recording state by the caller's
            // contract.
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
                dev.cmd_dispatch(cb, 1, 1, 1);

                // Make the shader writes visible to subsequent host
                // reads. Coherent memory does not need an explicit
                // invalidate, but we still need the execution barrier
                // and the access flag on the device side.
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

        // Read back into the caller's slice.
        bytemuck_like_copy_out(self.buffer.as_slice(), data);
        Ok(())
    }
}

impl Drop for GpuFft {
    fn drop(&mut self) {
        // SAFETY: by field order the OneShotCmd has already drained
        // the queue. Pool sets get freed by the descriptor pool when
        // RfCore drops; we still return our set to the pool to be
        // tidy. Any error during free is ignored because we are in
        // Drop.
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

/// Copy `src: &[Iq]` into the staging buffer's leading bytes.
/// Returns the number of bytes written. Out of line so the unsafe
/// block has one place to audit.
fn bytemuck_like_copy_in(dst: &mut [u8], src: &[Iq]) -> usize {
    let len = src.len() * core::mem::size_of::<Iq>();
    debug_assert!(dst.len() >= len);
    // SAFETY: Iq is repr(C) with size 8 and 4 byte align. The
    // destination buffer is at least `len` bytes long, host visible
    // and exclusively owned through &mut self at the call site.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr() as *const u8, dst.as_mut_ptr(), len);
    }
    len
}

/// Inverse of `bytemuck_like_copy_in`. Reads `dst.len()` Iq values.
fn bytemuck_like_copy_out(src: &[u8], dst: &mut [Iq]) {
    let len = dst.len() * core::mem::size_of::<Iq>();
    debug_assert!(src.len() >= len);
    // SAFETY: see bytemuck_like_copy_in. The source buffer is host
    // coherent so device writes are visible by the time we read.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr(), dst.as_mut_ptr() as *mut u8, len);
    }
}