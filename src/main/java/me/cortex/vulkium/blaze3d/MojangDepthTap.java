package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

import me.cortex.vulkium.vk.HzbTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies Mojang's current main-depth {@code VkImage} into HZB mip 0 with a depth→R32F
 * aspect-mask reinterpretation, so {@link me.cortex.vulkium.render.HzbBuilder} can then
 * compute the rest of the mip chain.
 *
 * <p>MC 26.2 removed {@code Minecraft.getMainRenderTarget()} — the current accessor is
 * {@link GameRenderer#mainRenderTarget()}. The returned {@link RenderTarget} exposes
 * {@link RenderTarget#getDepthTextureView()} (protected fields made public via getters).
 * We downcast the returned {@link GpuTextureView} to {@link VulkanGpuTextureView} and its
 * underlying {@link GpuTexture} to {@link VulkanGpuTexture} to reach the raw {@code VkImage}
 * handle — both classes expose the accessors publicly (see
 * {@code reference_mojang_vulkan_api.md}), so no accesswidener entry is required.
 *
 * <h2>Barrier contract</h2>
 * On success the call leaves:
 * <ul>
 *   <li>the Mojang depth attachment in {@code VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL}
 *       (Mojang's next consumer — the post-chain sampling the depth buffer — reads it as a
 *       sampled depth image, and this layout is compatible with that);</li>
 *   <li>HZB mip 0 in {@code VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL} — matching
 *       {@code HzbBuilder.recordBuildChain}'s documented precondition.</li>
 * </ul>
 *
 * <p><b>VUID concerns for review:</b>
 * <ul>
 *   <li>VUID-VkImageMemoryBarrier2-oldLayout — we assume the Mojang depth image was in
 *       {@code DEPTH_ATTACHMENT_OPTIMAL} (just-written by the main render pass). If vulkium
 *       ever runs this tap before the main pass finishes on a given frame, this is wrong.
 *       Frame ordering must guarantee this is called post-main-pass.</li>
 *   <li>VUID-vkCmdCopyImage-srcImage-07743 — the source aspect must be {@code DEPTH} only.
 *       We explicitly mask to DEPTH even when the format also has stencil (the extra aspect
 *       would fail the copy).</li>
 *   <li>VUID-vkCmdCopyImage-srcImage-01548 — src/dst format size compatibility. We guard by
 *       rejecting anything that isn't 32-bit float depth before scheduling the copy.</li>
 * </ul>
 */
public final class MojangDepthTap {

    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/depth-tap");

    /** Warn once when Mojang's depth format is unexpected — avoid log spam. */
    private static final AtomicBoolean WARNED_UNEXPECTED_FORMAT = new AtomicBoolean(false);
    private static final AtomicBoolean WARNED_NO_DEPTH = new AtomicBoolean(false);
    private static final AtomicBoolean WARNED_BACKEND_MISMATCH = new AtomicBoolean(false);

    private MojangDepthTap() {}

    /**
     * Record a copy from Mojang's current main depth {@code VkImage} into {@code hzb} mip 0.
     *
     * <p>Inserts the necessary image-layout barriers on both source and destination. Uses a
     * plain {@code vkCmdCopyImage} with {@code aspectMask = DEPTH} on the source and
     * {@code aspectMask = COLOR} on the destination — a bit-identical reinterpretation that
     * works only when both formats are 32-bit float (D32_SFLOAT ↔ R32_SFLOAT).
     *
     * @return {@code true} if the copy was recorded; {@code false} if Mojang's depth attachment
     *         wasn't accessible this frame, the format is incompatible, or anything else in the
     *         call chain was null (the caller should skip the HZB build).
     * @throws NullPointerException if {@code cmd} or {@code hzb} is {@code null} (programmer error).
     */
    public static boolean copyDepthToMip0(VkCommandBuffer cmd, HzbTexture hzb) {
        if (cmd == null) throw new NullPointerException("cmd");
        if (hzb == null) throw new NullPointerException("hzb");

        // 1. Resolve Minecraft.getInstance() → GameRenderer.mainRenderTarget(). MC 26.2
        //    removed Minecraft.getMainRenderTarget(); the replacement lives on GameRenderer.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        GameRenderer gr = mc.gameRenderer;
        if (gr == null) return false;
        RenderTarget mainTarget = gr.mainRenderTarget();
        if (mainTarget == null || !mainTarget.useDepth) return false;

        // 2. Reach the current depth GpuTextureView.
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        GpuTexture depthTex = mainTarget.getDepthTexture();
        if (depthView == null || depthTex == null) {
            if (WARNED_NO_DEPTH.compareAndSet(false, true)) {
                LOGGER.info("MojangDepthTap: main render target has no depth attachment this frame — HZB will be skipped.");
            }
            return false;
        }

        // 3. Downcast to the Vulkan backend's concrete types. If Mojang's backend is OpenGL
        //    (or some future non-Vulkan backend) these casts fail; we just bail out silently.
        //
        //    Both VulkanGpuTexture.vkImage() and VulkanGpuTextureView.texture() are public —
        //    no accesswidener entry needed. If a future MC refactor makes these package-private,
        //    the AW line required would be:
        //
        //    TODO(vulkium-aw): if these casts start failing at runtime, add to vulkium.accesswidener:
        //        accessible class com/mojang/blaze3d/vulkan/VulkanGpuTexture
        //        accessible class com/mojang/blaze3d/vulkan/VulkanGpuTextureView
        //        accessible method com/mojang/blaze3d/vulkan/VulkanGpuTexture vkImage ()J
        //    DO NOT add them speculatively — they're public today.
        if (!(depthView instanceof VulkanGpuTextureView vkDepthView)
                || !(depthTex instanceof VulkanGpuTexture vkDepthTex)) {
            if (WARNED_BACKEND_MISMATCH.compareAndSet(false, true)) {
                LOGGER.warn("MojangDepthTap: depth attachment is not a VulkanGpuTexture/View (got {} / {}). "
                        + "Running on non-Vulkan backend? HZB build skipped.",
                        depthView.getClass().getName(), depthTex.getClass().getName());
            }
            return false;
        }

        long srcImage = vkDepthTex.vkImage();
        if (srcImage == 0L) return false;

        // 4. Format validation. The copy aliasing DEPTH → COLOR is only bit-safe for 32-bit
        //    float depth. Anything else (D24_UNORM_S8_UINT, D16_UNORM, D32_FLOAT_S8_UINT)
        //    has different byte layout than R32_SFLOAT and would produce corrupted HZB data.
        GpuFormat srcFormat = vkDepthTex.getFormat();
        if (srcFormat != GpuFormat.D32_FLOAT) {
            if (WARNED_UNEXPECTED_FORMAT.compareAndSet(false, true)) {
                LOGGER.warn("MojangDepthTap: Mojang depth format is {} (expected D32_FLOAT). "
                        + "HZB requires 32-bit float depth; skipping build. If this persists, "
                        + "HzbTexture's format may need to be widened or a depth conversion pass "
                        + "inserted.", srcFormat);
            }
            return false;
        }

        // 5. Compute the copy extent: clip to the overlap of Mojang's current depth size and
        //    the HZB mip 0 size. Mojang's attachment resizes with the window; the HZB is
        //    allocated at a fixed size. Copying only the overlap is a safe conservative choice
        //    (the unsampled HZB texels stay whatever the previous frame left — mostly harmless
        //    because the occlusion test clips by mip footprint).
        int srcWidth = depthTex.getWidth(0);
        int srcHeight = depthTex.getHeight(0);
        int copyWidth = Math.min(srcWidth, hzb.width());
        int copyHeight = Math.min(srcHeight, hzb.height());
        if (copyWidth <= 0 || copyHeight <= 0) return false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 6. Pre-copy barrier: source depth → TRANSFER_SRC_OPTIMAL, HZB mip 0 → TRANSFER_DST_OPTIMAL.
            VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);

            // Source: assume it's just been written by the main render pass.
            VkImageMemoryBarrier2 srcPre = pre.get(0)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                    | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                // DEPTH_STENCIL_ATTACHMENT_OPTIMAL (VK10) — valid for depth-only images too.
                // Using this over VK12's dedicated DEPTH_ATTACHMENT_OPTIMAL keeps us at the
                // VK10 symbol boundary and works even if Mojang created the image with the
                // older layout. Per the Vulkan spec, either is a legal oldLayout for transfer.
                .oldLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(srcImage);
            srcPre.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            // Destination: HZB mip 0. UNDEFINED discards the previous contents (we overwrite them).
            VkImageMemoryBarrier2 dstPre = pre.get(1)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(VK13.VK_ACCESS_2_NONE)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(hzb.image());
            dstPre.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(pre);
            org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            // 7. vkCmdCopyImage — DEPTH → COLOR aspect reinterpretation, mip 0 → mip 0.
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            VkImageCopy r0 = region.get(0);
            r0.srcSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            r0.dstSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            r0.srcOffset().set(0, 0, 0);
            r0.dstOffset().set(0, 0, 0);
            r0.extent().set(copyWidth, copyHeight, 1);

            VK10.vkCmdCopyImage(cmd,
                srcImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                hzb.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region);

            // 8. Post-copy barrier on the source: TRANSFER_SRC_OPTIMAL → DEPTH_STENCIL_READ_ONLY_OPTIMAL.
            //    The HZB destination is LEFT in TRANSFER_DST_OPTIMAL per the HzbBuilder contract;
            //    HzbBuilder transitions it to SHADER_READ_ONLY_OPTIMAL as its phase-1 barrier.
            VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
            VkImageMemoryBarrier2 srcPost = post.get(0)
                .sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                    | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                    | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(srcImage);
            srcPost.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            VkDependencyInfo postDep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(post);
            org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, postDep);
        }

        return true;
    }
}
