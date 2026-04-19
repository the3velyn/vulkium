package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import me.cortex.vulkium.managers.SectionManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central per-frame entry point. Hooks Fabric's {@code LevelRenderEvents} so vulkium's render
 * pipeline runs alongside (and eventually in place of) MC's terrain draws.
 *
 * <p>For now this is observation only — drains the ingest queue on the render thread so captured
 * section meshes flow into {@link SectionManager#drainPending(int)}, and logs throughput stats
 * every so often. Once V7 phases come online, {@code afterOpaqueTerrain} is where vulkium's
 * mesh-shader draws go.
 */
public final class FrameDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/frame");

    /** Max section-ingest drains per frame — caps latency when a big chunk batch lands at once. */
    private static final int DRAIN_PER_FRAME = 256;

    private static final AtomicLong FRAMES = new AtomicLong();

    private FrameDriver() {}

    public static void register() {
        LevelRenderEvents.START_MAIN.register(FrameDriver::onStartMain);
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(FrameDriver::onAfterOpaqueTerrain);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(FrameDriver::onAfterTranslucentTerrain);
        LevelRenderEvents.END_MAIN.register(FrameDriver::onEndMain);
        LOGGER.info("FrameDriver hooks registered (START_MAIN, AFTER_OPAQUE_TERRAIN, AFTER_TRANSLUCENT_TERRAIN, END_MAIN).");
    }

    private static void onStartMain(LevelTerrainRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        FRAMES.incrementAndGet();

        // Ingest queue drain: move captured compile results from worker threads into the
        // render-thread-owned live section table + region ledger. MUST run every frame —
        // this is the only path from MC's worker-thread captures to our live state.
        SectionManager.get().drainPending(DRAIN_PER_FRAME);

        // prepareFrame runs the frustum cull + writes the scene UBO (+visibility pointers).
        // Only needed if we're drawing OR if F3 is shown (so the HUD overlay's visible-region
        // count stays fresh). When F3 is hidden AND draws are off, the idle frame cost is just
        // drainPending above.
        VulkiumConfig cfg = VulkiumConfig.get();
        boolean f3Shown = net.minecraft.client.Minecraft.getInstance().getDebugOverlay() != null
            && net.minecraft.client.Minecraft.getInstance().getDebugOverlay().showDebugScreen();
        if (cfg.drawTerrain || cfg.enableHzb || f3Shown) {
            Renderer.get().prepareFrame(ctx);
        }
    }

    private static void onAfterOpaqueTerrain(LevelTerrainRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        VulkiumConfig cfg = VulkiumConfig.get();

        // HZB build: tap Mojang's depth attachment (now fully populated after opaque terrain) +
        // run the downsample chain. Cheap (~100µs at 1080p) and provides the occlusion buffer
        // for future region/section visibility tests.
        if (cfg.enableHzb) {
            Renderer.get().buildHzb();
        }

        // Terrain draw moved to onEndMain — AFTER_OPAQUE_TERRAIN fires mid-world-render so
        // sky/entities/particles render AFTER and overwrite our output. END_MAIN fires after
        // all world drawing, before HUD — giving us a stable attachment to write into.
    }

    private static long lastDispatchLog = 0L;

    private static void dispatchTerrainDraw() {
        Renderer r = Renderer.get();
        me.cortex.vulkium.render.PrimaryTerrainPass pass = r.primaryTerrain();
        me.cortex.vulkium.render.SceneUniform scene = r.sceneUniform();
        VisibilityTracker vis = r.visibility();
        if (pass == null || scene == null || vis == null) {
            logDispatchThrottled("draw: null subsystem pass={} scene={} vis={}", pass, scene, vis);
            return;
        }
        int visibleRegionCount = vis.visibleRegionCount();
        if (visibleRegionCount == 0) {
            logDispatchThrottled("draw: visibleRegionCount=0 (cull rejected every region)");
            return;
        }

        // Dispatch count semantics: task shader uses gl_WorkGroupID.x AS a section ID, indexing
        // sectionData.data[sectionId]. So dispatch count must cover the whole addressable section
        // range (regionId<<8 | posInRegion), not just regionCount. Iterating all allocated regions
        // × 256 slots/region means empty slots no-op (renderRanges.w=0 → task emits 0 mesh
        // workgroups) and populated slots emit real geometry. Gives us ~40×256=10240 task
        // workgroups for a typical scene — fine for mesh-shader dispatch limits (65535+).
        me.cortex.vulkium.managers.RegionManager rm = me.cortex.vulkium.Vulkium.regionManager();
        int dispatchCount = rm == null ? visibleRegionCount
            : rm.maxRegionIndex() * me.cortex.vulkium.managers.RegionManager.SECTIONS_PER_REGION;
        if (dispatchCount == 0) return;

        logDispatchThrottled("draw: visibleRegions={} dispatchSections={} fbW={} fbH={}",
            visibleRegionCount, dispatchCount,
            me.cortex.vulkium.blaze3d.MojangColorFormat.width(),
            me.cortex.vulkium.blaze3d.MojangColorFormat.height());

        // Open our OWN render pass on Mojang's color attachment via vkCmdBeginRendering inside
        // a PRIMARY cmd buffer. Previous secondary-buffer approach assumed Fabric's
        // AFTER_OPAQUE_TERRAIN fires inside Mojang's render scope, but MC 26.2's
        // ChunkSectionsToRender.renderGroup creates+closes its own render pass per call —
        // the event fires BETWEEN passes, so secondaries with RENDER_PASS_CONTINUE silently fail.
        // Use mainRenderTarget's color view — that's the final framebuffer that gets presented
        // to the display. The view we captured from ChunkSectionsToRender was an intermediate
        // terrain texture that later gets composited; writing to it after its pass closed didn't
        // land on screen.
        net.minecraft.client.Minecraft mc2 = net.minecraft.client.Minecraft.getInstance();
        if (mc2 == null || mc2.gameRenderer == null || mc2.gameRenderer.mainRenderTarget() == null) return;
        com.mojang.blaze3d.pipeline.RenderTarget rt = mc2.gameRenderer.mainRenderTarget();
        com.mojang.blaze3d.textures.GpuTextureView gpuView2 = rt.getColorTextureView();
        if (!(gpuView2 instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView vkView2)) return;
        long colorView = vkView2.vkImageView();
        int colorFormat = com.mojang.blaze3d.vulkan.VulkanConst.toVk(vkView2.texture().getFormat());
        if (colorView == 0L) return;

        // Atlas is only needed for the real mesh draw; diag path does clears only. Allowing
        // null atlas lets the diag fire from the first frame.
        long atlasView = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasImageView();
        long atlasSampler = me.cortex.vulkium.blaze3d.MojangAtlasTap.sampler();

        final int fbW = rt.width;
        final int fbH = rt.height;
        final long colorViewHandle = colorView;
        final long colorImageHandle = vkView2.texture().vkImage();
        final long atlasViewFinal = atlasView;
        final long atlasSamplerFinal = atlasSampler;

        try {
            boolean ok = me.cortex.vulkium.vk.InlineRecorder.recordInPrimary(cmd -> {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    // DIAG 1: direct vkCmdClearColorImage blue — this worked last time.
                    org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer bToDst = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack)
                        .sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                        .srcAccessMask(0)
                        .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(colorImageHandle);
                    bToDst.subresourceRange()
                        .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                        org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(bToDst));

                    org.lwjgl.vulkan.VkClearColorValue blueClear = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
                    blueClear.float32(0, 0.0f).float32(1, 0.0f).float32(2, 1.0f).float32(3, 1.0f);
                    org.lwjgl.vulkan.VkImageSubresourceRange.Buffer blueRange = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack)
                        .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    org.lwjgl.vulkan.VK10.vkCmdClearColorImage(cmd, colorImageHandle,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blueClear, blueRange);

                    // DIAG 2: transition to COLOR_ATTACHMENT_OPTIMAL + try render-pass with
                    // LOAD_OP_CLEAR red. If we see red over the blue → render pass works.
                    // If we see blue → render-pass clear didn't land.
                    org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer enterB = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack)
                        .sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT)
                        .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                        .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(colorImageHandle);
                    enterB.subresourceRange()
                        .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                    org.lwjgl.vulkan.VkDependencyInfo enterDep = org.lwjgl.vulkan.VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pImageMemoryBarriers(enterB);
                    org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, enterDep);

                    // 2) vkCmdBeginRendering with just the color attachment.
                    // VULKIUM_DEBUG: CLEAR to bright red. If we see red anywhere in the frame,
                    // our render pass is actually reaching Mojang's displayed color attachment.
                    // If we don't see red, the view handle or submit path is wrong.
                    org.lwjgl.vulkan.VkClearValue.Buffer clearVal = org.lwjgl.vulkan.VkClearValue.calloc(1, stack);
                    clearVal.color().float32(0, 1.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);
                    org.lwjgl.vulkan.VkRenderingAttachmentInfo.Buffer colorAtt = org.lwjgl.vulkan.VkRenderingAttachmentInfo.calloc(1, stack)
                        .sType$Default()
                        .imageView(colorViewHandle)
                        .imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .resolveMode(0)
                        .loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE)
                        .clearValue(clearVal.get(0));

                    org.lwjgl.vulkan.VkRenderingInfo renderInfo = org.lwjgl.vulkan.VkRenderingInfo.calloc(stack)
                        .sType$Default()
                        .flags(0)
                        .layerCount(1)
                        .viewMask(0)
                        .pColorAttachments(colorAtt);
                    renderInfo.renderArea().offset().set(0, 0);
                    renderInfo.renderArea().extent().set(fbW, fbH);

                    org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderInfo);

                    // 3) Dynamic state + descriptor push + draw.
                    org.lwjgl.vulkan.VkViewport.Buffer vp = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                        .x(0f).y(0f).width(fbW).height(fbH).minDepth(0f).maxDepth(1f);
                    org.lwjgl.vulkan.VK10.vkCmdSetViewport(cmd, 0, vp);
                    org.lwjgl.vulkan.VkRect2D.Buffer sc = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
                    sc.offset().set(0, 0);
                    sc.extent().set(fbW, fbH);
                    org.lwjgl.vulkan.VK10.vkCmdSetScissor(cmd, 0, sc);

                    // DIAG: skip mesh-shader dispatch, leaving just the LOAD_OP_CLEAR red.
                    // If red appears → render pass works, issue is in pipeline/draw itself.
                    // If no red → the render-pass + barrier combination is still broken somehow.
                    if (false && atlasViewFinal != 0L && atlasSamplerFinal != 0L) {
                        me.cortex.vulkium.vk.PushDescriptor.builder(
                                pass.pipelineLayout().handle(),
                                org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                                1 /* set=1 textures */)
                            .combinedImageSampler(0, atlasViewFinal, atlasSamplerFinal,
                                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            .combinedImageSampler(1, atlasViewFinal, atlasSamplerFinal,
                                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            .push(cmd);
                        pass.record(cmd, scene, dispatchCount, false /* renderFog */);
                    }

                    // 4) End rendering. Do NOT transition back — Mojang's next sampler op will
                    //    do its own transition if needed (we left the attachment in COLOR_OPTIMAL
                    //    which is valid for subsequent begin-rendering loads).
                    org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
                }
            });
            if (!ok) {
                logDispatchThrottled("draw: Mojang primary cmd buffer not open — skipped");
            }
        } catch (Throwable t) {
            LOGGER.warn("Terrain draw failed (disabling drawTerrain this session)", t);
            VulkiumConfig.get().drawTerrain = false;
        }
    }

    private static void onAfterTranslucentTerrain(LevelRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        // V7 target: translucent mesh-shader pass goes here.
    }

    private static void onEndMain(LevelRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        // Flush this frame's pending host → device copies into an actual command-buffer submit
        // so the GPU sees the terrain arena writes + any scene-UBO-adjacent uploads before the
        // next frame's draws consume them.
        me.cortex.vulkium.vk.UploadStream stream = Renderer.get().uploadStream();
        if (stream != null) {
            try {
                stream.commitFrame();
            } catch (Throwable t) {
                LOGGER.warn("UploadStream.commitFrame failed", t);
            }
        }
        if (VulkiumConfig.get().drawTerrain) {
            dispatchTerrainDraw();
        }
    }

    public static long frameCount() { return FRAMES.get(); }

    /** Log at most once every ~1s during drawTerrain diagnostics. */
    private static void logDispatchThrottled(String fmt, Object... args) {
        long now = System.nanoTime();
        if (now - lastDispatchLog < 1_000_000_000L) return;
        lastDispatchLog = now;
        LOGGER.info(fmt, args);
    }
}
