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

    /** Fallback cap when cfg.drainPerFrame is absent/invalid. Vulkium suppresses vanilla
     *  terrain so any queued (not yet ingested) section = invisible chunk — default high. */
    private static final int DRAIN_PER_FRAME_FALLBACK = 4096;

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
        long tTotal = me.cortex.vulkium.diag.PerfTracker.begin();
        FRAMES.incrementAndGet();

        // GPU timer rotation: resolve ready pools (pushes gpu.* samples into PerfTracker) and
        // pick the next recording pool. Safe to call every frame whether or not any phase
        // records into it — begin()/end() are caller-side no-ops when activePool is -1.
        me.cortex.vulkium.diag.GpuTimerPool gpu = Renderer.get().gpuTimers();
        if (gpu != null) gpu.beginFrame();

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

        long tDrain = me.cortex.vulkium.diag.PerfTracker.begin();
        int drainCap = VulkiumConfig.get().drainPerFrame;
        if (drainCap <= 0) drainCap = DRAIN_PER_FRAME_FALLBACK;
        SectionManager.get().drainPending(drainCap);
        me.cortex.vulkium.diag.PerfTracker.end("drainPending", tDrain);

        long tResort = me.cortex.vulkium.diag.PerfTracker.begin();
        SectionManager.get().drainResorts(2048);
        me.cortex.vulkium.diag.PerfTracker.end("drainResorts", tResort);

        VulkiumConfig cfg = VulkiumConfig.get();
        boolean f3Shown = net.minecraft.client.Minecraft.getInstance().getDebugOverlay() != null
            && net.minecraft.client.Minecraft.getInstance().getDebugOverlay().showDebugScreen();
        if (cfg.drawTerrain || cfg.enableHzb || f3Shown) {
            long tPrep = me.cortex.vulkium.diag.PerfTracker.begin();
            Renderer.get().prepareFrame(ctx);
            me.cortex.vulkium.diag.PerfTracker.end("prepareFrame", tPrep);
        }
        me.cortex.vulkium.diag.PerfTracker.end("onStartMain", tTotal);
    }

    private static void onAfterOpaqueTerrain(LevelTerrainRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        long tTotal = me.cortex.vulkium.diag.PerfTracker.begin();
        VulkiumConfig cfg = VulkiumConfig.get();

        // Region-cull uses the HZB built at END_MAIN of the previous frame. It must run
        // BEFORE the opaque dispatch so the task shader's gate on regionVisibility sees the
        // updated bits. No-op when cfg.enableHzbRegionCull is false or the HZB doesn't exist
        // yet (first frame after enable, or HZB allocation failed — see Renderer.buildHzb).
        if (cfg.enableHzb && cfg.enableHzbRegionCull) {
            long tCull = me.cortex.vulkium.diag.PerfTracker.begin();
            Renderer.get().runRegionCull();
            me.cortex.vulkium.diag.PerfTracker.end("regionCull", tCull);
        } else {
            // Handle toggle-off transition: Renderer re-seeds regionVisibility to all-0xFF
            // if the previous frame ran cull and this frame won't. Cheap no-op otherwise.
            Renderer.get().runRegionCull();
        }

        if (!cfg.drawTerrain) {
            me.cortex.vulkium.diag.PerfTracker.end("onAfterOpaqueTerrain", tTotal);
            return;
        }
        Renderer r = Renderer.get();
        me.cortex.vulkium.render.SceneUniform scene = r.sceneUniform();
        var camState = ctx.levelState() != null ? ctx.levelState().cameraRenderState : null;
        if (scene != null && camState != null) {
            long tMvp = me.cortex.vulkium.diag.PerfTracker.begin();
            updateMvpFromCamera(scene, camState);
            me.cortex.vulkium.diag.PerfTracker.end("updateMvp", tMvp);
        }
        long tDraw = me.cortex.vulkium.diag.PerfTracker.begin();
        dispatchTerrainDraw(true, false);
        me.cortex.vulkium.diag.PerfTracker.end("dispatchOpaque", tDraw);
        me.cortex.vulkium.diag.PerfTracker.end("onAfterOpaqueTerrain", tTotal);
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
            boolean sortActive =
                me.cortex.vulkium.VulkiumConfig.get().translucencySortingLevel
                    != me.cortex.vulkium.config.TranslucencySortingLevel.NONE;
            int n = (ts != null && sortActive) ? ts.count() : 0;
            // Two cases:
            //  sortActive + count==0: no translucent sections in view — skip the dispatch.
            //  sortActive + count>0: dispatch exactly the sorted count, task shader redirects
            //    through sortingRegionList for back-to-front ordering.
            //  !sortActive (sort level = NONE): sorter.sort() never ran, so its count is 0
            //    and lastCount stays 0. sortingRegionListPtr in the scene UBO is 0L, so the
            //    translucent task shader falls through to gl_WorkGroupID.x (linear section
            //    order). We must dispatch the legacy full width — maxRegionIndex × 256 — so
            //    every live section's workgroup runs; most are no-ops on empty/opaque-only
            //    slots. Without this, NONE silently disables all translucent rendering
            //    because count() stays 0 → early return → no dispatch → no water/glass/ice.
            if (sortActive && n == 0 && ts != null) return;
            dispatchCount = (sortActive && n > 0)
                ? n
                : (rm == null ? visibleRegionCount
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
                        // VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT = 0x00080000,
                        // VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT = 0x00100000.
                        // DO NOT confuse with VK_SHADER_STAGE_TASK_BIT_EXT=0x40 / MESH=0x80 —
                        // different enum; those are descriptor/pipeline-layout bits. Using
                        // 0x40/0x80 here silently scopes the barrier to VERTEX_INPUT +
                        // VERTEX_SHADER and lets task/mesh reads race ahead of upload writes.
                        // Bit-value confusion, not symbol: VK13 doesn't alias the EXT mesh
                        // pipeline stages as constants, so we spell them out.
                        .dstStageMask(0x00080000L /* TASK_SHADER_BIT_EXT */
                                    | 0x00100000L /* MESH_SHADER_BIT_EXT */
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

                    me.cortex.vulkium.diag.GpuTimerPool gpu = Renderer.get().gpuTimers();
                    if (includeOpaque) {
                        if (gpu != null) gpu.begin(cmd, "opaqueDraw");
                        // Pick the fog-variant opaque pipeline when cfg.renderFog is on.
                        // Previously this was hardcoded to false → the no-fog pipeline
                        // always ran and the UBO's fog fields were never sampled, so
                        // enabling fog in the GUI had no visual effect regardless of the
                        // shader work landing in 0dc377f.
                        pass.record(cmd, scene, dispatchCount,
                                    VulkiumConfig.get().renderFog,
                                    atlasViewFinal, atlasSamplerFinal,
                                    lightmapViewFinal, lightmapSamplerFinal);
                        if (gpu != null) gpu.end(cmd, "opaqueDraw");
                    }
                    if (includeTranslucent) {
                        if (gpu != null) gpu.begin(cmd, "translucentDraw");
                        pass.recordTranslucent(cmd, scene, dispatchCount,
                                    atlasViewFinal, atlasSamplerFinal,
                                    lightmapViewFinal, lightmapSamplerFinal);
                        if (gpu != null) gpu.end(cmd, "translucentDraw");
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
        long tTotal = me.cortex.vulkium.diag.PerfTracker.begin();
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
        long tDraw = me.cortex.vulkium.diag.PerfTracker.begin();
        dispatchTerrainDraw(false, true);
        me.cortex.vulkium.diag.PerfTracker.end("dispatchTranslucent", tDraw);
        me.cortex.vulkium.diag.PerfTracker.end("onAfterTranslucentTerrain", tTotal);
    }

    /** Recompute the final MVP matrix (projection × pose × viewRotation) and push to the scene
     *  UBO. Shared between the translucent and opaque draw hooks — the MVP needs to incorporate
     *  MC's post-START_MAIN bob/distortion stack, which is only finalized once we reach the
     *  per-group render hooks. */
    private static void updateMvpFromCamera(me.cortex.vulkium.render.SceneUniform scene,
                                             net.minecraft.client.renderer.state.level.CameraRenderState camState) {
        // Use MC's UNMODIFIED projection so vulkium's terrain depth values match MC's
        // entity depth values exactly. Far-plane clipping is handled at the pipeline
        // level via depthClampEnable=true in PrimaryTerrainPass.
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
        // AFTER_TRANSLUCENT_TERRAIN. END_MAIN is where we build the HZB from the NOW-complete
        // depth buffer (sky + vulkium's opaque terrain + entities + translucent). That HZB is
        // read by next frame's region_cull at AFTER_OPAQUE_TERRAIN. Frame-late by one frame —
        // stale HZB over-reports visibility under camera motion, which is conservative.
        VulkiumConfig cfg = VulkiumConfig.get();
        if (cfg.enableHzb) {
            long tHzb = me.cortex.vulkium.diag.PerfTracker.begin();
            Renderer.get().buildHzb();
            me.cortex.vulkium.diag.PerfTracker.end("buildHzb", tHzb);
        }

        // Mark this frame's GPU timer pool ready for the next frame's beginFrame() to resolve.
        me.cortex.vulkium.diag.GpuTimerPool gpu = Renderer.get().gpuTimers();
        if (gpu != null) gpu.endFrame();
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
