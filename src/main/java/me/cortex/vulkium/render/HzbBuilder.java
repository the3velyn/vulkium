package me.cortex.vulkium.render;

import me.cortex.vulkium.vk.ComputeDispatch;
import me.cortex.vulkium.vk.HzbTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Builds the Hierarchical-Z buffer mip chain by dispatching the
 * {@code occlusion/hzb_downsample.comp} shader once per output mip level. Each dispatch samples
 * the previous mip with {@code textureGather} and writes the {@code max()} of the 2×2 block
 * into the next mip — conservative occlusion (farthest depth wins).
 *
 * <p>Mip 0 must be populated by the caller before {@link #recordBuildChain} (typically a
 * {@code vkCmdCopyImage} from Mojang's depth attachment into HZB mip 0). This builder takes
 * care of the layout transitions for mip 0 → SHADER_READ_ONLY and mips 1..N → GENERAL, plus
 * the per-iteration compute-write → compute-read barriers. It does <b>not</b> transition
 * anything back at the end — the occlusion-test consumer chooses its own post-layout to
 * match its own read pattern (a single whole-chain transition there is cheaper than us
 * guessing).
 *
 * <p>Uses sync2 barriers ({@link VK13#vkCmdPipelineBarrier2}).
 *
 * <p>Bypasses the {@code PushDescriptor} helper for descriptor updates because that helper
 * currently only exposes buffer + combined-image-sampler writes, and we need a storage-image
 * write for binding 1. We emit a single {@code vkCmdPushDescriptorSetKHR} call per dispatch
 * carrying both descriptor writes at once.
 */
public final class HzbBuilder implements AutoCloseable {

    // 2 × int32 = 8 bytes: ivec2 outSize
    private static final int PUSH_CONSTANT_SIZE = 8;

    private final ComputeDispatch dispatch;
    private boolean closed;

    public HzbBuilder() {
        this.dispatch = ComputeDispatch.create(
            "occlusion/hzb_downsample.comp",
            Map.of(),
            PUSH_CONSTANT_SIZE,
            List.of(
                new ComputeDispatch.Binding(0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1),
                new ComputeDispatch.Binding(1, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1)
            )
        );
    }

    /**
     * Record the downsample chain. Assumes HZB mip 0 is already populated (typically via a
     * {@code vkCmdCopyImage} from Mojang's depth attachment completed just before this call)
     * and is currently in {@code TRANSFER_DST_OPTIMAL}. Mips 1..N are assumed to be in
     * {@code UNDEFINED} (we clobber them anyway) and are transitioned to {@code GENERAL}
     * before any writes.
     */
    public void recordBuildChain(VkCommandBuffer cmd, HzbTexture hzb) {
        if (closed) throw new IllegalStateException("HzbBuilder is closed");
        int mipLevels = hzb.mipLevels();
        if (mipLevels <= 1) return; // nothing to build — mip 0 is already populated.

        long pipelineLayout = dispatch.layout().handle();
        long pipelineHandle = dispatch.pipeline().handle();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // --- Phase 1: initial layout setup.
            //
            //   Mip 0: TRANSFER_DST_OPTIMAL → SHADER_READ_ONLY_OPTIMAL. The copy that
            //          populated it ran on TRANSFER; we need it available as a sampler read
            //          in the first COMPUTE dispatch.
            //   Mips 1..N: UNDEFINED → GENERAL. These are about to be written as storage
            //          images and then re-read (as samplers, via the same storage view) on
            //          subsequent iterations. GENERAL is the only layout that supports both.
            //
            // Both barriers emitted in one vkCmdPipelineBarrier2 call.
            VkImageMemoryBarrier2.Buffer setup = VkImageMemoryBarrier2.calloc(2, stack);

            VkImageMemoryBarrier2 mip0 = setup.get(0)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(hzb.image());
            mip0.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkImageMemoryBarrier2 tail = setup.get(1)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(VK13.VK_ACCESS_2_NONE)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(hzb.image());
            tail.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(1).levelCount(mipLevels - 1)
                .baseArrayLayer(0).layerCount(1);

            VkDependencyInfo setupDep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(setup);
            VK13.vkCmdPipelineBarrier2(cmd, setupDep);

            // Bind the compute pipeline once for the whole chain.
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineHandle);

            // --- Phase 2: per-output-mip dispatch loop.
            //
            // Iteration N reads mip N-1 and writes mip N.
            //   N == 1: source layout is SHADER_READ_ONLY_OPTIMAL (mip 0, from phase 1).
            //   N >= 2: source layout is GENERAL (mip N-1, transitioned at end of previous
            //           iteration — or rather, stays in GENERAL; the write→read visibility
            //           barrier between iterations keeps the layout at GENERAL).
            for (int n = 1; n < mipLevels; n++) {
                int inMip = n - 1;
                int outW = hzb.mipWidth(n);
                int outH = hzb.mipHeight(n);
                int srcLayout = (n == 1)
                    ? VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    : VK10.VK_IMAGE_LAYOUT_GENERAL;

                // Push constants: ivec2 outSize.
                ByteBuffer pc = stack.calloc(PUSH_CONSTANT_SIZE);
                pc.putInt(0, outW).putInt(4, outH);
                VK10.vkCmdPushConstants(cmd, pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

                // Push both descriptor writes (sampler + storage image) in a single call.
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);

                // binding 0: combined image sampler for mip N-1 (read input).
                VkDescriptorImageInfo.Buffer samplerInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(hzb.storageView(inMip))
                    .sampler(hzb.sampler())
                    .imageLayout(srcLayout);
                writes.get(0)
                    .sType$Default()
                    .dstSet(0L)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(samplerInfo);

                // binding 1: storage image for mip N (write output).
                VkDescriptorImageInfo.Buffer storageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(hzb.storageView(n))
                    .sampler(VK10.VK_NULL_HANDLE)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(1)
                    .sType$Default()
                    .dstSet(0L)
                    .dstBinding(1)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(storageInfo);

                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0 /* set */, writes);

                int groupsX = (outW + 7) / 8;
                int groupsY = (outH + 7) / 8;
                VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);

                // --- Per-iteration barrier: make the write to mip N visible as a sampled
                // read for the next iteration, AND transition mip 0 from SHADER_READ_ONLY
                // → GENERAL on the first iteration so subsequent iterations can uniformly
                // use GENERAL for the source view.
                //
                // Actually, simpler: just barrier mip N (write → sampled read, layout
                // stays GENERAL). Mip 0's layout never needs to change again because only
                // iteration N==1 reads it, and it was already in SHADER_READ_ONLY for that
                // dispatch. No need to touch mip 0 again.
                if (n < mipLevels - 1) {
                    VkImageMemoryBarrier2.Buffer writeToRead =
                        VkImageMemoryBarrier2.calloc(1, stack);
                    VkImageMemoryBarrier2 b = writeToRead.get(0)
                        .sType$Default()
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(hzb.image());
                    b.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(n).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);

                    VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pImageMemoryBarriers(writeToRead);
                    VK13.vkCmdPipelineBarrier2(cmd, dep);
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        dispatch.close();
    }
}
