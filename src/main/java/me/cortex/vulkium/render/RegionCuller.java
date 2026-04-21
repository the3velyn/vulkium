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
 * GPU region-level occlusion cull against the HZB pyramid. Dispatches
 * {@code occlusion/region_cull.comp} with one thread per region; the shader writes
 * {@code regionVisibility.data[regionId]} = 0 (occluded) or 1 (visible), which the terrain
 * task shader reads as an early-exit gate before emitting mesh tasks.
 *
 * <p>Paired with the 1-frame-late HZB cycle wired in {@code FrameDriver}:
 * <ol>
 *   <li>Frame N end: {@code buildHzb()} captures frame N's full depth (including vulkium's
 *       own terrain — Mojang's main depth attachment is where we render).</li>
 *   <li>Frame N+1 at {@code AFTER_OPAQUE_TERRAIN}, before our opaque dispatch: this class's
 *       {@link #record} samples that HZB, updates regionVisibility.</li>
 *   <li>The opaque dispatch runs, its task shader gates on regionVisibility.</li>
 * </ol>
 *
 * <p>On the first frame with HZB enabled there's no previous frame's HZB; the caller skips
 * {@link #record} and leaves regionVisibility at its all-0xFF boot seed (= every region
 * visible). Second frame onward has valid data.
 *
 * <p>Conservative design: any AABB straddling the near plane, any corner behind the camera,
 * or any missing HZB texel gets marked visible. Mis-culling disappears terrain instantly,
 * so we only cull on proof, never on heuristic.
 */
public final class RegionCuller implements AutoCloseable {

    /** Matches shader local_size_x. */
    private static final int WORKGROUP_SIZE = 64;

    private final ComputeDispatch dispatch;
    private boolean closed;

    public RegionCuller() {
        this.dispatch = ComputeDispatch.create(
            "occlusion/region_cull.comp",
            Map.of(),
            0,
            List.of(
                new ComputeDispatch.Binding(0, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1),
                new ComputeDispatch.Binding(1, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1)
            )
        );
    }

    /**
     * Record the region_cull dispatch. Caller must have (a) built the HZB from a prior frame
     * and transitioned it to {@code SHADER_READ_ONLY_OPTIMAL} (or GENERAL, both are sampleable),
     * and (b) flushed any host-side updates to regionData / regionVisibility via the upload
     * stream.
     *
     * <p>Issues a buffer memory barrier after the dispatch so the subsequent task-shader reads
     * of {@code regionVisibility} observe the writes from this shader. The barrier is narrow
     * (compute-shader-write → mesh-task-shader-read) to avoid stalling unrelated work.
     *
     * @param regionUpperBound the (exclusive) upper bound of region IDs to process, typically
     *                         {@link me.cortex.vulkium.managers.RegionManager#maxRegionIndex}
     *                         so every live ID (including reused slots) gets culled. Zero
     *                         skips the dispatch (Vulkan forbids vkCmdDispatch(0, ...)).
     */
    public void record(VkCommandBuffer cmd,
                       SceneUniform sceneUniform,
                       HzbTexture hzb,
                       int regionUpperBound) {
        if (closed) throw new IllegalStateException("RegionCuller is closed");
        if (regionUpperBound <= 0 || hzb == null) return;

        int groupsX = (regionUpperBound + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;

        final long sceneBuffer = sceneUniform.buffer().handle();
        final long hzbView = hzb.sampledView();
        final long hzbSampler = hzb.sampler();

        // HzbBuilder leaves mip 0 in SHADER_READ_ONLY_OPTIMAL (populated from TRANSFER at
        // setup, then read as a sampler in iteration 1) and mips 1..N-1 in GENERAL (written
        // as storage images and re-read as sampled images across the chain). Our sampledView
        // covers all mips and the descriptor's imageLayout must match every accessed subresource,
        // so transition mips 1..N-1 from GENERAL → SHADER_READ_ONLY_OPTIMAL. Mip 0 is already
        // there. Degenerate case (mipLevels == 1) skips the barrier — mip 0 is already in
        // SHADER_READ_ONLY and mip 0 alone is the sampledView.
        if (hzb.mipLevels() > 1) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
                barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(hzb.image());
                barrier.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(1).levelCount(hzb.mipLevels() - 1)
                    .baseArrayLayer(0).layerCount(1);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
            }
        }

        dispatch.record(
            cmd,
            null,
            bindings -> bindings
                .uniformBuffer(0, sceneBuffer, 0L, SceneUniform.SCENE_UBO_SIZE)
                .combinedImageSampler(1, hzbView, hzbSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),
            groupsX, 1, 1);

        // Make the regionVisibility writes visible to the subsequent task-shader reads.
        // regionVisibility is reached via buffer-device-address, so the barrier is memory
        // (not per-buffer) — we don't have a VkBuffer handle for a BDA region without
        // plumbing one in from Renderer, and a memory barrier on the stage mask is the
        // idiomatic fix.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer mb = VkMemoryBarrier2.calloc(1, stack)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                // VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT = 0x00080000 (bit 19)
                // VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT = 0x00100000 (bit 20).
                // DO NOT confuse with VK_SHADER_STAGE_TASK_BIT_EXT=0x40 / MESH=0x80 — those
                // are descriptor/pipeline-layout bits and carry different semantics. Using
                // 0x40/0x80 here incorrectly scopes the barrier to VERTEX_INPUT + VERTEX_SHADER
                // instead of task/mesh, so compute writes to regionVisibility aren't made
                // visible to the task-shader reads that actually gate on them. Silent on
                // permissive drivers (NVIDIA Ampere); GPU hang on stricter drivers
                // (NVIDIA Blackwell / RTX 5080 crash 2026-04-21 13:07:58).
                .dstStageMask(0x00080000L | 0x00100000L)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT);
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
