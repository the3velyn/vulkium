package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.RegionManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton owning render-thread-side resources: scene UBO, (future) pipelines, (future) GPU
 * upload ring. Created lazily on first frame because VK backend must be up first.
 *
 * <p>Created on first {@link #prepareFrame} call; closed from {@code Vulkium.onClientStopping}
 * via {@link #shutdown()}.
 */
public final class Renderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/render");
    private static final Renderer INSTANCE = new Renderer();

    private SceneUniform sceneUniform;
    private VisibilityTracker visibility;
    private boolean initFailed;

    private Renderer() {}

    public static Renderer get() { return INSTANCE; }

    /** Populate the scene UBO for this frame from MC's render state. Called at START_MAIN. */
    public void prepareFrame(AbstractLevelRenderContext ctx) {
        if (initFailed) return;
        ensureInit();
        if (sceneUniform == null) return;

        LevelRenderState state = ctx.levelState();
        if (state == null || state.cameraRenderState == null) return;
        CameraRenderState cam = state.cameraRenderState;

        // MVP = projection * viewRotation. Vulkium's terrain shaders pass positions in
        // camera-relative section coords; subchunkOffset carries the fractional camera offset.
        Matrix4f mvp = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix);

        Vec3 pos = cam.pos == null ? Vec3.ZERO : cam.pos;
        int cx = (int) Math.floor(pos.x) >> 4;
        int cy = (int) Math.floor(pos.y) >> 4;
        int cz = (int) Math.floor(pos.z) >> 4;
        float fx = (float) (pos.x - ((long) cx << 4));
        float fy = (float) (pos.y - ((long) cy << 4));
        float fz = (float) (pos.z - ((long) cz << 4));

        RegionManager rm = Vulkium.regionManager();
        long regionPtr = rm != null ? rm.regionBufferAddress() : 0L;
        long sectionPtr = rm != null ? rm.sectionBufferAddress() : 0L;
        int regionCount = rm != null ? rm.regionCount() : 0;

        // Region-level frustum + distance cull. Uses MC's cullFrustum directly — no sodium dep.
        if (cam.cullFrustum != null && rm != null) {
            int rd = net.minecraft.client.Minecraft.getInstance().options.renderDistance().get();
            visibility.update(cam.cullFrustum, cx, cy, cz, rd);
        }

        sceneUniform
            .mvp(mvp)
            .chunkPosition(cx, cy, cz, 0)
            .subchunkOffset(fx, fy, fz, 0.0f)
            .fogColour(0, 0, 0, 0)
            // Buffer-reference pointers populated as each subsystem lands its buffers.
            .regionIndicesPtr(0L)
            .regionDataPtr(regionPtr)
            .sectionDataPtr(sectionPtr)
            .regionVisibilityPtr(0L)
            .sectionVisibilityPtr(0L)
            .terrainCmdPtr(0L)
            .translucencyCmdPtr(0L)
            .sortingRegionListPtr(0L)
            .terrainDataPtr(0L)
            .transformationArrPtr(0L)
            .originArrPtr(0L)
            .statisticsPtr(0L)
            .screenSize(1f, 1f)
            .fog(0f, 1f, false)
            .regionCount(regionCount)
            .frameId((int) (FrameDriver.frameCount() & 0xFF));
        sceneUniform.flush();
    }

    public SceneUniform sceneUniform() { return sceneUniform; }
    public VisibilityTracker visibility() { return visibility; }

    private void ensureInit() {
        if (sceneUniform != null || initFailed) return;
        try {
            sceneUniform = new SceneUniform();
            visibility = new VisibilityTracker();
            LOGGER.info("Renderer initialized (SceneUniform allocated, {} bytes; VisibilityTracker ready).",
                SceneUniform.SCENE_UBO_SIZE);
        } catch (Throwable t) {
            LOGGER.error("Renderer init failed (marking as failed, vulkium rendering paths will no-op)", t);
            initFailed = true;
        }
    }

    public void shutdown() {
        if (sceneUniform != null) {
            try { sceneUniform.close(); } catch (Throwable t) { LOGGER.warn("SceneUniform close failed", t); }
            sceneUniform = null;
        }
    }
}
