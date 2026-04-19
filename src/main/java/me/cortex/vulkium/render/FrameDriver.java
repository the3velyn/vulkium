package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
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

    /** Log stats every N frames. Roughly once every ~17s @ 60fps. */
    private static final int LOG_EVERY = 1024;

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
        long frame = FRAMES.incrementAndGet();

        // Ingest queue drain: move captured compile results from worker threads into the
        // render-thread-owned live section table + region ledger.
        int drained = SectionManager.get().drainPending(DRAIN_PER_FRAME);

        // Populate scene UBO for this frame. Safe to call even when pipelines aren't wired —
        // this just updates the host-mapped buffer; it only costs GPU bandwidth when a
        // subsequent pipeline actually reads from it.
        Renderer.get().prepareFrame(ctx);

        if (frame <= 4 || frame % LOG_EVERY == 0) {
            SectionManager sm = SectionManager.get();
            LOGGER.info("Frame {}: drained={} liveSections={} regions={}",
                frame, drained, sm.liveView().size(),
                Vulkium.regionManager() != null ? Vulkium.regionManager().regionCount() : 0);
        }
    }

    private static void onAfterOpaqueTerrain(LevelTerrainRenderContext ctx) {
        if (!Vulkium.isEnabled()) return;
        // V7 target: dispatch vulkium's 6-phase mesh-shader pipeline here via
        // VulkanCommandEncoder.allocateTransientCommandBuffer(false) + encoder.execute(cmd).
        // Today this is a no-op — vanilla MC's terrain has already been drawn.
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
