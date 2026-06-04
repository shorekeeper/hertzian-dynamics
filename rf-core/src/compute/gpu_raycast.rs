//! GPU batched raycast pipeline.
//!
//! Wraps shaders/raycast.comp. Owns fixed query, result, and material
//! buffers and a descriptor set. The voxel directory and pool come from a
//! GpuVoxelGrid and are bound per execute, since the mirror may have been
//! resynced. The query, result, and material buffers are bound once at
//! construction.
//!
//! `execute` blocks on a per-call fence. The standalone primitive runs at
//! the caller's cadence; the structure mirrors the other GPU pipelines and
//! can adopt async submission later.

use ash::vk;

use crate::compute::gpu_voxel::GpuVoxelGrid;
use crate::error::{vk_err, Result, RfCoreError};
use crate::propagation::material::MaterialTable;
use crate::vulkan::{HostBuffer, MemoryAllocator, OneShotCmd, ShaderModule, VulkanDevice};

const RAYCAST_SPV: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/raycast.spv"));

const WG_SIZE: u32 = 64;

/// Maximum rays per batch. Larger batches are rejected; the caller chunks
/// or falls back to CPU.
pub const GPU_RAYCAST_MAX_QUERIES: usize = 8192;

/// Maximum material ids the GPU table holds.
pub const GPU_RAYCAST_MAX_MATERIALS: usize = 1024;

const QUERY_STRIDE: usize = 8;
const MATERIAL_STRIDE: usize = 4;

#[repr(C)]
#[derive(Copy, Clone)]
struct PushConstants {
    freq: f32,
    budget: f32,
    voxel: f32,
    dir_capacity: u32,
    material_count: u32,
    query_count: u32,
}

const _: () = {
    assert!(core::mem::size_of::<PushConstants>() == 24);
};

/// GPU raycast pipeline.
pub struct GpuRaycast {
    cmd: OneShotCmd,
    pipeline: vk::Pipeline,
    pipeline_layout: vk::PipelineLayout,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_set: vk::DescriptorSet,
    descriptor_pool: vk::DescriptorPool,
    queries: HostBuffer,
    results: HostBuffer,
    materials: HostBuffer,
    device: ash::Device,
}

impl GpuRaycast {
    /// Build the pipeline and allocate the fixed query, result, and
    /// material buffers.
    /// Build the pipeline and allocate the fixed query, result, and
    /// material buffers.
    pub fn new(
        device: &VulkanDevice,
        pipeline_cache: vk::PipelineCache,
        descriptor_pool: vk::DescriptorPool,
    ) -> Result<Self> {
        let raw = device.raw();
        let shader = ShaderModule::from_spirv_bytes(device, RAYCAST_SPV)?;

        let mut bindings = Vec::with_capacity(5);
        for b in 0..5u32 {
            bindings.push(
                vk::DescriptorSetLayoutBinding::default()
                    .binding(b)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE),
            );
        }
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
        let q_bytes = (GPU_RAYCAST_MAX_QUERIES * QUERY_STRIDE * 4) as vk::DeviceSize;
        let r_bytes = (GPU_RAYCAST_MAX_QUERIES * 4) as vk::DeviceSize;
        let m_bytes = (GPU_RAYCAST_MAX_MATERIALS * MATERIAL_STRIDE * 4) as vk::DeviceSize;
        let queries = HostBuffer::new(device, &allocator, q_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;
        let results = HostBuffer::new(device, &allocator, r_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;
        let materials = HostBuffer::new(device, &allocator, m_bytes, vk::BufferUsageFlags::STORAGE_BUFFER)?;

        let set_layouts = [descriptor_set_layout];
        let alloc_info = vk::DescriptorSetAllocateInfo::default()
            .descriptor_pool(descriptor_pool)
            .set_layouts(&set_layouts);
        // SAFETY: pool is alive for the lifetime of RfCore.
        let sets = unsafe { raw.allocate_descriptor_sets(&alloc_info) }
            .map_err(|r| vk_err("vkAllocateDescriptorSets", r))?;
        let descriptor_set = sets[0];

        // Bind the fixed buffers (queries at 0, results at 1, materials at
        // 4) once. The grid directory (2) and pool (3) are bound per
        // execute because the mirror may have been resynced.
        let q_info = [vk::DescriptorBufferInfo::default()
            .buffer(queries.handle())
            .offset(0)
            .range(q_bytes)];
        let r_info = [vk::DescriptorBufferInfo::default()
            .buffer(results.handle())
            .offset(0)
            .range(r_bytes)];
        let m_info = [vk::DescriptorBufferInfo::default()
            .buffer(materials.handle())
            .offset(0)
            .range(m_bytes)];
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&q_info),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&r_info),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(4)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&m_info),
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
            queries,
            results,
            materials,
            device: raw.clone(),
        })
    }

    /// Run a batch. `grid` must already be synced. `queries` holds
    /// `count` packed rays (8 f32 each). `out` receives `count` absorption
    /// values in dB.
    pub fn execute(
        &mut self,
        grid: &GpuVoxelGrid,
        materials: &MaterialTable,
        queries: &[f32],
        count: usize,
        frequency_hz: f64,
        budget_db: f32,
        out: &mut [f32],
    ) -> Result<()> {
        if count == 0 || count > GPU_RAYCAST_MAX_QUERIES || queries.len() < count * QUERY_STRIDE {
            return Err(RfCoreError::InvalidArgument("raycast batch size out of range"));
        }
        let mat_count = materials.entries().len().min(GPU_RAYCAST_MAX_MATERIALS);

        // Stage queries.
        copy_f32_in(self.queries.as_slice_mut(), &queries[..count * QUERY_STRIDE]);

        // Stage materials.
        {
            let dst = f32_view_mut(self.materials.as_slice_mut(), mat_count * MATERIAL_STRIDE);
            for (i, m) in materials.entries().iter().take(mat_count).enumerate() {
                let b = i * MATERIAL_STRIDE;
                dst[b] = m.atten_db_per_m_at_ref;
                dst[b + 1] = m.reference_frequency_hz;
                dst[b + 2] = m.scaling_exponent;
                dst[b + 3] = m.pivot_frequency_hz;
            }
        }

        // Bind the grid directory and pool for this batch.
        let (dir_buf, dir_range) = grid.dir_buffer();
        let (pool_buf, pool_range) = grid.pool_buffer();
        let dir_info = [vk::DescriptorBufferInfo::default().buffer(dir_buf).offset(0).range(dir_range)];
        let pool_info = [vk::DescriptorBufferInfo::default().buffer(pool_buf).offset(0).range(pool_range)];
        let grid_writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(self.descriptor_set)
                .dst_binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&dir_info),
            vk::WriteDescriptorSet::default()
                .dst_set(self.descriptor_set)
                .dst_binding(3)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(&pool_info),
        ];
        // grid_writes reference local stack data; descriptor set is
        // not in use by any in-flight submission (the previous execute
        // fenced before returning).
        unsafe { self.device.update_descriptor_sets(&grid_writes, &[]) };

        let pc = PushConstants {
            freq: frequency_hz as f32,
            budget: budget_db,
            voxel: grid.voxel_size(),
            dir_capacity: grid.dir_capacity(),
            material_count: mat_count as u32,
            query_count: count as u32,
        };
        // SAFETY: PushConstants is repr(C) size 24 with no padding holes.
        let pc_bytes = unsafe {
            std::slice::from_raw_parts(
                (&pc as *const PushConstants) as *const u8,
                core::mem::size_of::<PushConstants>(),
            )
        };

        let pipeline = self.pipeline;
        let pipeline_layout = self.pipeline_layout;
        let descriptor_set = self.descriptor_set;
        let groups = (count as u32).div_ceil(WG_SIZE);

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

        copy_f32_out(self.results.as_slice(), &mut out[..count]);
        Ok(())
    }
}

impl Drop for GpuRaycast {
    fn drop(&mut self) {
        // SAFETY: by field order the command machinery drained the queue.
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

fn f32_view_mut(bytes: &mut [u8], len: usize) -> &mut [f32] {
    // SAFETY: host visible memory, 4 byte aligned, at least 4*len bytes.
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr() as *mut f32, len) }
}

fn copy_f32_in(dst: &mut [u8], src: &[f32]) {
    let len = src.len() * 4;
    debug_assert!(dst.len() >= len);
    // SAFETY: dst is host visible with at least len bytes.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr() as *const u8, dst.as_mut_ptr(), len);
    }
}

fn copy_f32_out(src: &[u8], dst: &mut [f32]) {
    let len = dst.len() * 4;
    debug_assert!(src.len() >= len);
    // SAFETY: src is host coherent, device writes visible.
    unsafe {
        std::ptr::copy_nonoverlapping(src.as_ptr(), dst.as_mut_ptr() as *mut u8, len);
    }
}