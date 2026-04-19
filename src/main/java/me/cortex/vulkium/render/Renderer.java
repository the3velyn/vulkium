package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.vk.UploadStream;
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
    private RegionSorter regionSorter;
    private PrimaryTerrainPass primaryTerrain;
    private TerrainUploader terrainUploader;
    private UploadStream uploadStream;
    private boolean initFailed;

    /** Upload-ring section size (bytes) × count. Balances per-frame peak upload vs memory. */
    private static final long UPLOAD_SECTION_BYTES = 16L * 1024 * 1024;   // 16 MB / frame peak
    private static final int  UPLOAD_SECTION_COUNT = 3;                   // triple-buffered

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
        long terrainPtr = terrainUploader != null ? terrainUploader.arenaBuffer().deviceAddress() : 0L;
        long sortListPtr = regionSorter != null && uploadStream != null
            ? regionSorter.uploadVisibleList(uploadStream, visibility)
            : 0L;

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
            .sortingRegionListPtr(sortListPtr)
            .terrainDataPtr(terrainPtr)
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
    public RegionSorter regionSorter() { return regionSorter; }
    public PrimaryTerrainPass primaryTerrain() { return primaryTerrain; }
    public TerrainUploader terrainUploader() { return terrainUploader; }
    public UploadStream uploadStream() { return uploadStream; }

    private void ensureInit() {
        if (sceneUniform != null || initFailed) return;
        try {
            sceneUniform = new SceneUniform();
            visibility = new VisibilityTracker();
            uploadStream = UploadStream.create(UPLOAD_SECTION_BYTES, UPLOAD_SECTION_COUNT);
            // Eager shader compile: any shaderc / pipeline failure surfaces here, not on a
            // first-draw crash mid-frame. Construct failure disables the whole Renderer
            // (leaves vulkium flag enabled but render paths no-op so the game still runs).
            regionSorter = new RegionSorter();
            primaryTerrain = new PrimaryTerrainPass();
            terrainUploader = new TerrainUploader();
            LOGGER.info(
                "Renderer initialized: SceneUniform({}B) + VisibilityTracker + UploadStream({}MB×{}) + "
                    + "RegionSorter + PrimaryTerrainPass + TerrainUploader(128MB arena).",
                SceneUniform.SCENE_UBO_SIZE, UPLOAD_SECTION_BYTES / (1024 * 1024), UPLOAD_SECTION_COUNT);
        } catch (Throwable t) {
            LOGGER.error("Renderer init failed (marking as failed, vulkium rendering paths will no-op)", t);
            initFailed = true;
        }
    }

    public void shutdown() {
        // Close in reverse construction order so dependent VK handles teardown before their
        // predecessors (pipelines hold references to modules + VkDevice; uploader holds its
        // arena's DeviceBuffer; UploadStream holds a StagingBuffer).
        if (terrainUploader != null) {
            try { terrainUploader.close(); } catch (Throwable t) { LOGGER.warn("TerrainUploader close failed", t); }
            terrainUploader = null;
        }
        if (primaryTerrain != null) {
            try { primaryTerrain.close(); } catch (Throwable t) { LOGGER.warn("PrimaryTerrainPass close failed", t); }
            primaryTerrain = null;
        }
        if (regionSorter != null) {
            try { regionSorter.close(); } catch (Throwable t) { LOGGER.warn("RegionSorter close failed", t); }
            regionSorter = null;
        }
        if (uploadStream != null) {
            try { uploadStream.close(); } catch (Throwable t) { LOGGER.warn("UploadStream close failed", t); }
            uploadStream = null;
        }
        if (sceneUniform != null) {
            try { sceneUniform.close(); } catch (Throwable t) { LOGGER.warn("SceneUniform close failed", t); }
            sceneUniform = null;
        }
    }
}
