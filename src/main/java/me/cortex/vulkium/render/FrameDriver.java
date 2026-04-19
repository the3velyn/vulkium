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

        // Vulkium terrain draw. Gated behind cfg.drawTerrain — default off because the
        // fragment shader's tex_light binding has no lightmap tap yet (we bind the block
        // atlas to both diffuse + light slots for now; result is visually wrong but
        // structurally valid).
        if (cfg.drawTerrain) {
            dispatchTerrainDraw();
        }
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

        // Inheritance must match what Mojang's vkCmdBeginRendering set up. Tap the real color
        // format via ChunkSectionsToRenderMixin.capturedColorVkFormat. First frame before the
        // mixin fires uses the PrimaryTerrainPass default as fallback — no harm; on first
        // vanilla-cancel call the format gets captured for all subsequent frames.
        int colorFormat = me.cortex.vulkium.blaze3d.MojangColorFormat.get();
        if (colorFormat == 0) colorFormat = me.cortex.vulkium.render.PrimaryTerrainPass.COLOR_FORMAT;

        me.cortex.vulkium.vk.SecondaryRecorder.InheritanceSpec spec =
            new me.cortex.vulkium.vk.SecondaryRecorder.InheritanceSpec(
                new int[] { colorFormat },
                me.cortex.vulkium.render.PrimaryTerrainPass.DEPTH_FORMAT,
                org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED,
                org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT);

        long atlasView = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasImageView();
        long atlasSampler = me.cortex.vulkium.blaze3d.MojangAtlasTap.sampler();
        if (atlasView == 0L || atlasSampler == 0L) return;

        // MeshPipeline uses DYNAMIC viewport + scissor — must set them in the secondary cmd
        // buffer before draw. Size MUST match Mojang's color-attachment extent, NOT the window
        // (MC 26.2 renders terrain into a 2048x2048 internal texture). Use the mixin's captured
        // values; fall back to window dims if not captured yet.
        int w0 = me.cortex.vulkium.blaze3d.MojangColorFormat.width();
        int h0 = me.cortex.vulkium.blaze3d.MojangColorFormat.height();
        if (w0 == 0 || h0 == 0) {
            w0 = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
            h0 = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
        }
        final int fbW = w0;
        final int fbH = h0;

        try {
            me.cortex.vulkium.vk.SecondaryRecorder.recordAndSubmit(spec, cmd -> {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.vulkan.VkViewport.Buffer vp = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                        .x(0f).y(0f).width(fbW).height(fbH).minDepth(0f).maxDepth(1f);
                    org.lwjgl.vulkan.VK10.vkCmdSetViewport(cmd, 0, vp);

                    org.lwjgl.vulkan.VkRect2D.Buffer sc = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
                    sc.offset().set(0, 0);
                    sc.extent().set(fbW, fbH);
                    org.lwjgl.vulkan.VK10.vkCmdSetScissor(cmd, 0, sc);
                }

                me.cortex.vulkium.vk.PushDescriptor.builder(
                        pass.pipelineLayout().handle(),
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                        1 /* set=1 textures */)
                    .combinedImageSampler(0, atlasView, atlasSampler,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .combinedImageSampler(1, atlasView, atlasSampler,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
                pass.record(cmd, scene, dispatchCount, false /* renderFog */);
            });
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
