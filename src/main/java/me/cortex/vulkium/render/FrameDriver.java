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

    private static void dispatchTerrainDraw() {
        Renderer r = Renderer.get();
        me.cortex.vulkium.render.PrimaryTerrainPass pass = r.primaryTerrain();
        me.cortex.vulkium.render.SceneUniform scene = r.sceneUniform();
        VisibilityTracker vis = r.visibility();
        if (pass == null || scene == null || vis == null) return;
        int visibleRegionCount = vis.visibleRegionCount();
        if (visibleRegionCount == 0) return;

        // Inheritance must match what Mojang's current vkCmdBeginRendering set up. MC 26.2's
        // main world pass uses R8G8B8A8_UNORM color + D32_SFLOAT depth (matches vulkium's
        // PrimaryTerrainPass defaults). Driver format-mismatch would fail the execute —
        // swapping to a query once we have a Mojang-internal state tap.
        me.cortex.vulkium.vk.SecondaryRecorder.InheritanceSpec spec =
            new me.cortex.vulkium.vk.SecondaryRecorder.InheritanceSpec(
                new int[] { me.cortex.vulkium.render.PrimaryTerrainPass.COLOR_FORMAT },
                me.cortex.vulkium.render.PrimaryTerrainPass.DEPTH_FORMAT,
                org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED,
                org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT);

        long atlasView = me.cortex.vulkium.blaze3d.MojangAtlasTap.blockAtlasImageView();
        long atlasSampler = me.cortex.vulkium.blaze3d.MojangAtlasTap.sampler();
        if (atlasView == 0L || atlasSampler == 0L) return;

        try {
            me.cortex.vulkium.vk.SecondaryRecorder.recordAndSubmit(spec, cmd -> {
                me.cortex.vulkium.vk.PushDescriptor.builder(
                        pass.pipelineLayout().handle(),
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                        1 /* set=1 textures */)
                    .combinedImageSampler(0, atlasView, atlasSampler,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .combinedImageSampler(1, atlasView, atlasSampler,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
                pass.record(cmd, scene, visibleRegionCount, false /* renderFog */);
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
}
