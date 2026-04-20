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

    /** Max section-ingest drains per frame. Vulkium suppresses vanilla terrain so any queued
     *  (not yet ingested) section = invisible chunk. High cap keeps streaming smooth on RD
     *  change or teleport. */
    private static final int DRAIN_PER_FRAME = 4096;

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

        // NB: the BobViewTap reset lives in GameRendererBobMixin at bobHurt HEAD, NOT here.
        // Fabric's LevelRenderEvents.START_MAIN fires from inside LevelRenderer.renderLevel,
        // which GameRenderer.renderLevel calls AFTER bobHurt+bobView have already captured this
        // frame's pose. An invalidate() here would wipe their capture before FrameDriver.onEndMain
        // reads it, leaving MVP without any bob contribution.

        // Initialize the renderer up-front so the ingest drain below has a live TerrainUploader
        // to hand sections to. prepareFrame also runs ensureInit, but it's gated on
        // drawTerrain / enableHzb / F3-overlay — without this eager call, sections compiled
        // during the first few frames (before the user opens F3 or flips a toggle) would be
        // popped from the ingest queue with a null uploader and silently dropped, leaving
        // those chunks invisible until MC re-issued them via a block edit or F3+A.
        Renderer.get().ensureReady();

        // Ingest queue drain: move captured compile results from worker threads into the
        // render-thread-owned live section table + region ledger. MUST run every frame —
        // this is the only path from MC's worker-thread captures to our live state.
        SectionManager.get().drainPending(DRAIN_PER_FRAME);

        // Translucent POV-resort drain: MC's ResortTransparencyTask produces new sorted index
        // buffers each time the camera crosses a sub-chunk boundary. Our resort mixin copies
        // those into a queue; here we apply them to the arena by permuting the cached unsorted
        // translucent bytes. Cap liberally — resort batches are typically tens of sections.
        SectionManager.get().drainResorts(2048);

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
        // run the downsample chain.
        if (cfg.enableHzb) {
            Renderer.get().buildHzb();
        }

        if (!cfg.drawTerrain) return;
        // Draw opaque HERE (MC's own opaque was cancelled by ChunkSectionsToRenderMixin). This
        // is the vanilla position for opaque terrain — sky/entities/clouds/translucent come
        // after and depth-test against our output. Critical for correct cloud/water layering:
        // if opaque runs at END_MAIN instead, clouds draw first with empty depth and end up
        // occluding our translucent at AFTER_TRANSLUCENT_TERRAIN.
        Renderer r = Renderer.get();
        me.cortex.vulkium.render.SceneUniform scene = r.sceneUniform();
        var camState = ctx.levelState() != null ? ctx.levelState().cameraRenderState : null;
        if (scene != null && camState != null) {
            updateMvpFromCamera(scene, camState);
        }
        // commitFrame moved to the end of Renderer.prepareFrame (START_MAIN) — submitting
        // earlier overlaps the copies with MC's sky / opaque / entities / clouds pass.
        dispatchTerrainDraw(true, false);
    }

    private static long lastDispatchLog = 0L;
    private static boolean depthFormatLogged = false;
    private static boolean atlasInfoLogged = false;

    /** Split draw: opaque-only, translucent-only, or both in a single render pass. */
    private static void dispatchTerrainDraw(boolean includeOpaque, boolean includeTranslucent) {
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

        // Dispatch count: both passes now consume compact lists — opaque via OpaqueDispatchList,
        // translucent via TranslucentSectionSorter's sorted list. Task shaders redirect
        // gl_WorkGroupID.x through their respective list and no-op on 0xFFFFFFFF sentinel, so
        // the dispatch width can shrink to the actual entry count.
        me.cortex.vulkium.managers.RegionManager rm = me.cortex.vulkium.Vulkium.regionManager();
        int dispatchCount;
        if (includeOpaque && !includeTranslucent) {
            OpaqueDispatchList list = r.opaqueDispatchList();
            int n = list != null ? list.count() : 0;
            dispatchCount = n > 0 ? n : (rm == null ? visibleRegionCount
                : rm.maxRegionIndex() * me.cortex.vulkium.managers.RegionManager.SECTIONS_PER_REGION);
        } else if (includeTranslucent && !includeOpaque) {
            me.cortex.vulkium.render.TranslucentSectionSorter ts =
                me.cortex.vulkium.render.Renderer.get().translucentSorter();
            int n = ts != null ? ts.count() : 0;
            // Fall back to legacy width only if sorter is unavailable; count==0 is legitimate
            // (no translucent in view) and should skip the dispatch entirely.
            if (n == 0 && ts != null) return;
            dispatchCount = n > 0 ? n : (rm == null ? visibleRegionCount
                : rm.maxRegionIndex() * me.cortex.vulkium.managers.RegionManager.SECTIONS_PER_REGION);
        } else {
            dispatchCount = rm == null ? visibleRegionCount
                : rm.maxRegionIndex() * me.cortex.vulkium.managers.RegionManager.SECTIONS_PER_REGION;
        }
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
        com.mojang.blaze3d.pipeline.RenderTarget mainRt = mc2.gameRenderer.mainRenderTarget();

        // Color target: opaque writes to MAIN, translucent writes to TRANSLUCENT. Mirrors MC
        // vanilla's per-layer target bundle — MC composites CLOUDS/TRANSLUCENT via PostChain in
        // the right relative order, so vulkium writing to the same per-layer targets makes the
        // final composite land water in front of clouds where closer.
        //
        // Depth: both layers depth-test against MAIN's depth view. MC's clouds/entities share
        // this depth too, so depth-sort between vulkium's geometry and MC's world effects
        // stays coherent.
        com.mojang.blaze3d.pipeline.RenderTarget colorRt = mainRt;
        if (includeTranslucent && !includeOpaque) {
            try {
                if (mc2.levelRenderer != null) {
                    com.mojang.blaze3d.pipeline.RenderTarget tRt = mc2.levelRenderer.translucentTarget();
                    if (tRt != null) {
                        colorRt = tRt;
                    }
                }
            } catch (Throwable ignored) {
                // Some configurations (e.g. fabulous-graphics off) have a null translucent target.
                // Fall through to main. Users get the pre-target behavior (cloud-over-water).
            }
        }
        com.mojang.blaze3d.textures.GpuTextureView gpuView2 = colorRt.getColorTextureView();
        if (!(gpuView2 instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView vkView2)) return;
        long colorView = vkView2.vkImageView();
        int colorFormat = com.mojang.blaze3d.vulkan.VulkanConst.toVk(vkView2.texture().getFormat());
        if (colorView == 0L) return;

        // Depth always from the main render target — every MC world-render pass shares this
        // depth buffer, so depth-tests against clouds/entities/etc. remain consistent.
        long depthView = 0L;
        long depthImage = 0L;
        int depthFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
        com.mojang.blaze3d.textures.GpuTextureView depthGpuView = mainRt.getDepthTextureView();
        if (depthGpuView instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView vkDepth) {
            depthView = vkDepth.vkImageView();
            depthImage = vkDepth.texture().vkImage();
            depthFormat = com.mojang.blaze3d.vulkan.VulkanConst.toVk(vkDepth.texture().getFormat());
            if (!depthFormatLogged) {
                depthFormatLogged = true;
                LOGGER.info("Mojang depth attachment: VkFormat={} view=0x{}", depthFormat, Long.toHexString(depthView));
            }
        }

        long atlasView = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasImageView();
        long atlasSampler = me.cortex.vulkium.blaze3d.MojangAtlasTap.sampler();
        long lightmapView = me.cortex.vulkium.blaze3d.MojangLightmapTap.lightmapImageView();
        long lightmapSampler = me.cortex.vulkium.blaze3d.MojangLightmapTap.sampler();
        long atlasImage = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasImage();
        int atlasMipLevels = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasMipLevels();
        if (atlasView != 0L && !atlasInfoLogged) {
            atlasInfoLogged = true;
            LOGGER.info("Atlas: view=0x{} image=0x{} format=VkFormat({}) mipLevels={} sampler=0x{}",
                Long.toHexString(atlasView), Long.toHexString(atlasImage),
                me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasVkFormat(),
                atlasMipLevels, Long.toHexString(atlasSampler));
        }

        final int fbW = colorRt.width;
        final int fbH = colorRt.height;
        final long colorViewHandle = colorView;
        final long colorImageHandle = vkView2.texture().vkImage();
        final long depthViewHandle = depthView;
        final long depthImageHandle = depthImage;
        final int depthFormatFinal = depthFormat;
        final long atlasViewFinal = atlasView;
        final long atlasSamplerFinal = atlasSampler;
        final long atlasImageFinal = atlasImage;
        final int atlasMipFinal = atlasMipLevels;
        final long lightmapViewFinal = lightmapView;
        final long lightmapSamplerFinal = lightmapSampler;

        try {
            me.cortex.vulkium.vk.CommandRecorder.recordAndSubmit(cmd -> {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    // Color + depth barriers only. Atlas barrier was disrupting Mojang's layout
                    // tracking for the block atlas, causing GPU hangs — Mojang keeps the atlas
                    // in SHADER_READ_ONLY_OPTIMAL anyway for its own terrain pass.
                    int barrierCount = 1
                        + (depthViewHandle != 0L ? 1 : 0);
                    org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer bars = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(barrierCount, stack);
                    bars.position(0).sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                        .srcAccessMask(0)
                        .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                                     | org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT)
                        .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(colorImageHandle);
                    bars.position(0).subresourceRange()
                        .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                    int barIdx = 1;
                    if (depthViewHandle != 0L) {
                        bars.position(barIdx).sType$Default()
                            .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                            .srcAccessMask(0)
                            .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                                        | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                            .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                                         | org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                            .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                            .image(depthImageHandle);
                        bars.position(barIdx).subresourceRange()
                            .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1);
                        barIdx++;
                    }
                    bars.position(0);
                    // Global buffer/memory barrier in addition to image layouts: UploadStream's
                    // vkCmdCopyBuffer submissions run on the same queue BEFORE us, but cross-
                    // submit execution order alone doesn't guarantee cache coherence between
                    // TRANSFER writes and SHADER reads. Without this, task/mesh/fragment stages
                    // occasionally read stale arena, sectionBuffer or regionBuffer data — the
                    // symptom matches "new chunks render holes" (their freshly-uploaded headers
                    // aren't visible yet) and the sporadic translucent artifacts (stale sort-list
                    // entries). Cover both: transfer-dst writes AND host writes (our sort list
                    // is host-mapped BDA; some GPUs still need an explicit host → device fence).
                    org.lwjgl.vulkan.VkMemoryBarrier2.Buffer memBars = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
                    memBars.position(0).sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT
                                    | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                        .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                                     | org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_WRITE_BIT)
                        .dstStageMask(0x00000040 /* TASK_SHADER_BIT_EXT */
                                    | 0x00000080 /* MESH_SHADER_BIT_EXT */
                                    | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                                    | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
                                     | org.lwjgl.vulkan.VK13.VK_ACCESS_2_UNIFORM_READ_BIT);
                    org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                        org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default()
                            .pMemoryBarriers(memBars)
                            .pImageMemoryBarriers(bars));

                    // LOAD_OP_LOAD to preserve Mojang's sky/clouds composite. Our draws layer
                    // on top with depth test against Mojang's depth buffer.
                    org.lwjgl.vulkan.VkRenderingAttachmentInfo.Buffer colorAtt = org.lwjgl.vulkan.VkRenderingAttachmentInfo.calloc(1, stack)
                        .sType$Default()
                        .imageView(colorViewHandle)
                        .imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .resolveMode(0)
                        .loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE);

                    org.lwjgl.vulkan.VkRenderingAttachmentInfo depthAtt = null;
                    if (depthViewHandle != 0L) {
                        depthAtt = org.lwjgl.vulkan.VkRenderingAttachmentInfo.calloc(stack)
                            .sType$Default()
                            .imageView(depthViewHandle)
                            .imageLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .resolveMode(0)
                            .loadOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                            .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE);
                    }

                    org.lwjgl.vulkan.VkRenderingInfo renderInfo = org.lwjgl.vulkan.VkRenderingInfo.calloc(stack)
                        .sType$Default()
                        .flags(0)
                        .layerCount(1)
                        .viewMask(0)
                        .pColorAttachments(colorAtt)
                        .pDepthAttachment(depthAtt);
                    renderInfo.renderArea().offset().set(0, 0);
                    renderInfo.renderArea().extent().set(fbW, fbH);

                    org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderInfo);

                    org.lwjgl.vulkan.VkViewport.Buffer vp = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                        .x(0f).y(0f).width(fbW).height(fbH).minDepth(0f).maxDepth(1f);
                    org.lwjgl.vulkan.VK10.vkCmdSetViewport(cmd, 0, vp);
                    org.lwjgl.vulkan.VkRect2D.Buffer sc = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
                    sc.offset().set(0, 0);
                    sc.extent().set(fbW, fbH);
                    org.lwjgl.vulkan.VK10.vkCmdSetScissor(cmd, 0, sc);

                    if (includeOpaque) {
                        pass.record(cmd, scene, dispatchCount, false /* renderFog */,
                                    atlasViewFinal, atlasSamplerFinal,
                                    lightmapViewFinal, lightmapSamplerFinal);
                    }
                    if (includeTranslucent) {
                        pass.recordTranslucent(cmd, scene, dispatchCount,
                                    atlasViewFinal, atlasSamplerFinal,
                                    lightmapViewFinal, lightmapSamplerFinal);
                    }
                    logDispatchThrottled("draw: opaque={} translucent={} dispatchCount={} atlas={}",
                        includeOpaque, includeTranslucent, dispatchCount, atlasViewFinal != 0L);

                    org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
                }
            });
        } catch (Throwable t) {
            LOGGER.warn("Terrain draw failed (disabling drawTerrain this session)", t);
            VulkiumConfig.get().drawTerrain = false;
        }
    }

    private static void onAfterTranslucentTerrain(LevelRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        if (!VulkiumConfig.get().drawTerrain) return;
        // Draw translucent HERE, not at END_MAIN. MC's cloud renderer fires between opaque and
        // translucent in vanilla order; drawing translucent at END_MAIN (after clouds) was the
        // plan but clouds were rendering OVER water. Moving the translucent draw to
        // AFTER_TRANSLUCENT_TERRAIN places it exactly where MC's own translucent pass would sit —
        // after clouds, before weather/particles — so our water / glass / ice depth-tests against
        // the cloud depth already in the buffer and blends on top correctly.
        //
        // Opaque still runs at END_MAIN (sees sky+clouds already composited, uses depth-test to
        // sort against them). Scene UBO with the final MVP is written at END_MAIN's start, so
        // we need to update it here too before the translucent dispatch.
        Renderer r = Renderer.get();
        me.cortex.vulkium.render.SceneUniform scene = r.sceneUniform();
        var camState = ctx.levelState() != null ? ctx.levelState().cameraRenderState : null;
        if (scene != null && camState != null) {
            updateMvpFromCamera(scene, camState);
        }
        // Second commitFrame skipped — no UploadStream writes land between opaque hook and
        // translucent hook. The opaque hook's commit already pushed this frame's arena /
        // region-header / dispatch-list writes to the GPU. Dropping this commit removes one
        // command-buffer submission per frame.
        dispatchTerrainDraw(false, true);
    }

    /** Recompute the final MVP matrix (projection × pose × viewRotation) and push to the scene
     *  UBO. Shared between the translucent and opaque draw hooks — the MVP needs to incorporate
     *  MC's post-START_MAIN bob/distortion stack, which is only finalized once we reach the
     *  per-group render hooks. */
    private static void updateMvpFromCamera(me.cortex.vulkium.render.SceneUniform scene,
                                             net.minecraft.client.renderer.state.level.CameraRenderState camState) {
        org.joml.Matrix4f mvp;
        org.joml.Matrix4f captured = new org.joml.Matrix4f();
        if (me.cortex.vulkium.blaze3d.BobViewTap.readProjection(captured)) {
            mvp = captured.mul(camState.viewRotationMatrix);
        } else {
            org.joml.Matrix4f pose = new org.joml.Matrix4f();
            boolean havePose = me.cortex.vulkium.blaze3d.BobViewTap.readPose(pose);
            mvp = new org.joml.Matrix4f(camState.projectionMatrix);
            if (havePose) mvp.mul(pose);
            mvp.mul(camState.viewRotationMatrix);
        }
        scene.mvp(mvp);
        scene.flushMvp();  // narrow flush — only MVP changed since prepareFrame's full flush
    }

    private static void onEndMain(LevelRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        // Draws moved to per-phase hooks: opaque at AFTER_OPAQUE_TERRAIN, translucent at
        // AFTER_TRANSLUCENT_TERRAIN. END_MAIN is now just a tail-end anchor — no draws here.
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
