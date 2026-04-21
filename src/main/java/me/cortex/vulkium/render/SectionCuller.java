package me.cortex.vulkium.render;

import me.cortex.vulkium.vk.ComputeDispatch;
import me.cortex.vulkium.vk.HzbTexture;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.KHRSynchronization2;

import java.util.List;
import java.util.Map;

/**
 * Section-level HZB cull. Runs AFTER {@link RegionCuller} in the same cmd buffer batch;
 * refines the region-level visibility bits into per-section bits by testing each
 * populated section's 16³-block AABB against the previous frame's HZB.
 *
 * <p>Dispatch model: one workgroup per allocated region slot, {@code local_size_x = 256}
 * so the WG's 256 threads each handle one {@code compactId} in that region. Early-out in
 * the shader on {@code regionVisibility == 0} so whole-region-occluded dispatches cost
 * essentially nothing.
 *
 * <p>Output fills {@code sectionVisibility.data[(regionId << 8) | compactId]} with 0 or 1.
 * The existing task-shader gate in {@code terrain/task.glsl} already ANDs regionVisibility
 * with sectionVisibility — no consumer-side change needed.
 *
 * <p>Barrier responsibility: like {@link RegionCuller}, emits a memory barrier at the
 * end of {@link #record} so the task-shader reads of sectionVisibility observe these
 * writes. Layout transition on the HZB is skipped here — {@code RegionCuller} already
 * transitioned the full mip chain to SHADER_READ_ONLY_OPTIMAL in the same cmd buffer,
 * and this class runs right after.
 */
public final class SectionCuller implements AutoCloseable {

    /** Matches shader local_size_x. Equal to
     *  {@link me.cortex.vulkium.managers.RegionManager#SECTIONS_PER_REGION}. */
    private static final int WORKGROUP_SIZE = 256;

    private final ComputeDispatch dispatch;
    private boolean closed;

    public SectionCuller() {
        this.dispatch = ComputeDispatch.create(
            "occlusion/section_cull.comp",
            Map.of(),
            0,
            List.of(
                new ComputeDispatch.Binding(0, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1),
                new ComputeDispatch.Binding(1, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1)
            )
        );
    }

    /**
     * Record the section_cull dispatch. Precondition: the HZB is in
     * {@code SHADER_READ_ONLY_OPTIMAL} across all mips (caller, typically
     * {@link RegionCuller}, emits that transition earlier in the same cmd buffer).
     * Preceding {@link RegionCuller#record} also emits a memory barrier for compute-write →
     * task-shader-read on {@code regionVisibility}; we reuse that ordering here (compute →
     * compute is a no-op on NVIDIA, but we add our own explicit barrier before the
     * dispatch to cover the regionVisibility read ordering on drivers that need it).
     *
     * @param regionUpperBound exclusive upper bound on region slots to visit — typically
     *     {@link me.cortex.vulkium.managers.RegionManager#maxRegionIndex}. Threads past
     *     the populated count within each region early-exit via the sectionEmpty check in
     *     the shader.
     */
    public void record(VkCommandBuffer cmd,
                       SceneUniform sceneUniform,
                       HzbTexture hzb,
                       int regionUpperBound) {
        if (closed) throw new IllegalStateException("SectionCuller is closed");
        if (regionUpperBound <= 0 || hzb == null) return;

        // Barrier: region_cull's writes to regionVisibility must be visible to our
        // reads. compute-write → compute-read via memory barrier (no image transition;
        // HZB is already SHADER_READ_ONLY from RegionCuller's setup).
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer mb = VkMemoryBarrier2.calloc(1, stack)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
            VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pMemoryBarriers(mb);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
        }

        final long sceneBuffer = sceneUniform.buffer().handle();
        final long hzbView = hzb.sampledView();
        final long hzbSampler = hzb.sampler();

        dispatch.record(
            cmd,
            null,
            bindings -> bindings
                .uniformBuffer(0, sceneBuffer, 0L, SceneUniform.SCENE_UBO_SIZE)
                .combinedImageSampler(1, hzbView, hzbSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            regionUpperBound, 1, 1);

        // sectionVisibility writes → task-shader reads (both graphics shader stages + the
        // forthcoming TRANSFER_READ for the host-readback copy). Memory-scoped barrier
        // covers the BDA-addressed buffer uniformly.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer mb = VkMemoryBarrier2.calloc(1, stack)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                // VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT = 0x00080000,
                // VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT = 0x00100000. See note in
                // RegionCuller: prior values 0x40/0x80 were the VkShaderStageFlagBits for
                // task/mesh, NOT the pipeline-stage bits, and scoped this barrier to the
                // wrong stages — OK on Ampere, GPU-hang on Blackwell.
                .dstStageMask(0x00080000L | 0x00100000L
                            | VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT
                             | VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
            VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pMemoryBarriers(mb);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        dispatch.close();
    }
}
