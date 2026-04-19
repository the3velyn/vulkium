package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import me.cortex.vulkium.blaze3d.MojangDepthTap;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.vk.CommandRecorder;
import me.cortex.vulkium.vk.HzbTexture;
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
    private HzbBuilder hzbBuilder;
    private HzbTexture hzbTexture;
    /** Small device buffer holding {@link RegionManager#MAX_TRANSFORMATION_COUNT} mat4 entries;
     *  index 0 seeded with identity so sections with transformationId=0 transform as no-op. */
    private me.cortex.vulkium.vk.DeviceBuffer transformationBuffer;
    /** Device buffer for originArray (packed origin offsets per id). Seeded to zero. */
    private me.cortex.vulkium.vk.DeviceBuffer originBuffer;
    /** section/region visibility buffers — task shader gates all emissions on these being non-zero.
     *  Seeded all-0xFF = "everything visible" so the task shader's `shouldRenderVisible` returns true
     *  without depending on the GPU-side culling pipeline. Replaced by the real occlusion results
     *  once V8 HZB-based section culling lands. */
    private me.cortex.vulkium.vk.DeviceBuffer sectionVisibilityBuffer;
    private me.cortex.vulkium.vk.DeviceBuffer regionVisibilityBuffer;
    private int hzbWidth;
    private int hzbHeight;
    private long hzbLastBuildNs;
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

        // Drain dirty regions into the GPU-side regionBuffer + sectionBuffer. SectionManager
        // marks regions dirty whenever it populates a section header — without this upload
        // step our CPU-side writes never reach shader visibility.
        if (rm != null && uploadStream != null) {
            final var target = rm;
            rm.drainDirty((regionId, removed, metaSrc, sectionSrc) -> {
                long metaDst = uploadStream.upload(
                    target.regionBuffer(),
                    (long) regionId * RegionManager.META_SIZE,
                    RegionManager.META_SIZE);
                org.lwjgl.system.MemoryUtil.memCopy(metaSrc, metaDst, RegionManager.META_SIZE);
                if (!removed) {
                    long sectionDst = uploadStream.upload(
                        target.sectionBuffer(),
                        (long) regionId * RegionManager.TOTAL_SECTION_META_SIZE,
                        RegionManager.TOTAL_SECTION_META_SIZE);
                    org.lwjgl.system.MemoryUtil.memCopy(sectionSrc, sectionDst,
                        RegionManager.TOTAL_SECTION_META_SIZE);
                } else {
                    // Removed region → zero the slab so the GPU can't read stale data.
                    long sectionDst = uploadStream.upload(
                        target.sectionBuffer(),
                        (long) regionId * RegionManager.TOTAL_SECTION_META_SIZE,
                        RegionManager.TOTAL_SECTION_META_SIZE);
                    org.lwjgl.system.MemoryUtil.memSet(sectionDst, 0,
                        RegionManager.TOTAL_SECTION_META_SIZE);
                }
            });
        }

        sceneUniform
            .mvp(mvp)
            // nvidium convention: chunkPosition is stored in (X, Z, Y) order so that the
            // shader's `chunk -= chunkPosition.xyz` subtraction matches the axis order of
            // `ivec3(header.xyz) >> 8` (which is (chunkX, chunkZ, chunkY) per scene.glsl's
            // Section struct comment).
            .chunkPosition(cx, cz, cy, 0)
            .subchunkOffset(fx, fy, fz, 0.0f)
            .fogColour(0, 0, 0, 0)
            // Buffer-reference pointers populated as each subsystem lands its buffers.
            .regionIndicesPtr(0L)
            .regionDataPtr(regionPtr)
            .sectionDataPtr(sectionPtr)
            .regionVisibilityPtr(regionVisibilityBuffer != null ? regionVisibilityBuffer.deviceAddress() : 0L)
            .sectionVisibilityPtr(sectionVisibilityBuffer != null ? sectionVisibilityBuffer.deviceAddress() : 0L)
            .terrainCmdPtr(0L)
            .translucencyCmdPtr(0L)
            .sortingRegionListPtr(sortListPtr)
            .terrainDataPtr(terrainPtr)
            .transformationArrPtr(transformationBuffer != null ? transformationBuffer.deviceAddress() : 0L)
            .originArrPtr(originBuffer != null ? originBuffer.deviceAddress() : 0L)
            .statisticsPtr(0L)
            // nvidium convention: screenSize is HALF the framebuffer resolution in pixels.
            // Mesh shader bbox cull does `((pos.xy/pos.w)+1) * screenSize` → NDC [-1..1] +1 = [0..2]
            // then × (W/2, H/2) = [0..W, 0..H] pixel coords. With (1,1) every quad lands in
            // sub-pixel coords and gets culled as degenerate.
            .screenSize(
                me.cortex.vulkium.blaze3d.MojangColorFormat.width()  * 0.5f,
                me.cortex.vulkium.blaze3d.MojangColorFormat.height() * 0.5f)
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
    public HzbTexture hzbTexture() { return hzbTexture; }
    public long hzbLastBuildNs() { return hzbLastBuildNs; }

    /**
     * Tap Mojang's depth attachment into HZB mip 0 + run the downsample chain. Called from
     * {@code FrameDriver} at AFTER_OPAQUE_TERRAIN when {@link VulkiumConfig#enableHzb} is on.
     * Lazily (re-)allocates the HZB texture to match the current framebuffer size.
     */
    public void buildHzb() {
        if (initFailed) return;
        int w = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
        int h = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
        if (w <= 0 || h <= 0) return;

        if (hzbTexture == null || w != hzbWidth || h != hzbHeight) {
            // Size changed (or first call). Drop the old texture + rebuild.
            if (hzbTexture != null) {
                try { hzbTexture.close(); } catch (Throwable t) { LOGGER.warn("HzbTexture close failed", t); }
                hzbTexture = null;
            }
            try {
                hzbTexture = HzbTexture.allocate(w, h);
                hzbWidth = w;
                hzbHeight = h;
                if (hzbBuilder == null) hzbBuilder = new HzbBuilder();
                LOGGER.info("HZB (re)allocated at {}x{} ({} mips).", w, h, hzbTexture.mipLevels());
            } catch (Throwable t) {
                LOGGER.error("HZB allocation failed; disabling HZB path", t);
                hzbTexture = null;
                return;
            }
        }

        long t0 = System.nanoTime();
        try {
            final HzbTexture hzb = this.hzbTexture;
            final HzbBuilder builder = this.hzbBuilder;
            CommandRecorder.recordAndSubmit(cmd -> {
                if (!MojangDepthTap.copyDepthToMip0(cmd, hzb)) {
                    // Depth not tappable this frame — skip build; mip 0 is not in the expected layout.
                    return;
                }
                builder.recordBuildChain(cmd, hzb);
            });
        } catch (Throwable t) {
            LOGGER.warn("HZB build failed (continuing without occlusion this frame)", t);
        }
        hzbLastBuildNs = System.nanoTime() - t0;
    }

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

            // Seed transformationArray[0] with an identity mat4 so sections whose
            // transformationId=0 transform as identity (no-op). Without this, the mesh shader
            // reads zeros → transformMat is zero matrix → every vertex collapses to origin →
            // no visible geometry.
            int transformationCount = me.cortex.vulkium.managers.RegionManager.MAX_TRANSFORMATION_COUNT;
            transformationBuffer = me.cortex.vulkium.vk.DeviceBuffer.allocate(
                (long) transformationCount * 64L /* sizeof(mat4) */);
            seedIdentityTransformation(transformationBuffer);

            // originArray — one uint64 per entry. Seed to zero; task shaders that unpack from it
            // get a valid (0,0,0) offset. Entries are populated later if we ever drive them.
            originBuffer = me.cortex.vulkium.vk.DeviceBuffer.allocate(
                (long) transformationCount * 8L);
            // Already zeroed by VMA allocation.

            // Visibility buffers: task shader gates on sectionVisibility.data[id] & 1 != 0.
            // Seed all-0xFF so every section is considered visible. Sized for max regions
            // (1024 * 256 = 262,144 bytes of section bits; 1024 bytes for region bits).
            int maxRegions = me.cortex.vulkium.VulkiumConfig.get().maxRegions;
            sectionVisibilityBuffer = me.cortex.vulkium.vk.DeviceBuffer.allocate(
                (long) maxRegions * me.cortex.vulkium.managers.RegionManager.SECTIONS_PER_REGION);
            regionVisibilityBuffer = me.cortex.vulkium.vk.DeviceBuffer.allocate((long) maxRegions);
            seedFillByte(sectionVisibilityBuffer, (byte) 0xFF);
            seedFillByte(regionVisibilityBuffer,  (byte) 0xFF);

            LOGGER.info(
                "Renderer initialized: SceneUniform({}B) + VisibilityTracker + UploadStream({}MB×{}) + "
                    + "RegionSorter + PrimaryTerrainPass + TerrainUploader(128MB arena) + "
                    + "transformationBuffer(identity) + originBuffer.",
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
        if (hzbTexture != null) {
            try { hzbTexture.close(); } catch (Throwable t) { LOGGER.warn("HzbTexture close failed", t); }
            hzbTexture = null;
        }
        if (hzbBuilder != null) {
            try { hzbBuilder.close(); } catch (Throwable t) { LOGGER.warn("HzbBuilder close failed", t); }
            hzbBuilder = null;
        }
        if (transformationBuffer != null) {
            try { transformationBuffer.close(); } catch (Throwable t) { LOGGER.warn("transformationBuffer close failed", t); }
            transformationBuffer = null;
        }
        if (originBuffer != null) {
            try { originBuffer.close(); } catch (Throwable t) { LOGGER.warn("originBuffer close failed", t); }
            originBuffer = null;
        }
        if (sectionVisibilityBuffer != null) {
            try { sectionVisibilityBuffer.close(); } catch (Throwable t) { LOGGER.warn("sectionVisibilityBuffer close failed", t); }
            sectionVisibilityBuffer = null;
        }
        if (regionVisibilityBuffer != null) {
            try { regionVisibilityBuffer.close(); } catch (Throwable t) { LOGGER.warn("regionVisibilityBuffer close failed", t); }
            regionVisibilityBuffer = null;
        }
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

    /**
     * Stage an identity mat4 at index 0 of {@code transformationBuffer} through the upload
     * stream; commit immediately so it's resident by the first frame. Identity = (1,0,0,0 /
     * 0,1,0,0 / 0,0,1,0 / 0,0,0,1) column-major.
     */
    private void seedIdentityTransformation(me.cortex.vulkium.vk.DeviceBuffer buf) {
        long dst = uploadStream.upload(buf, 0L, 64);
        org.lwjgl.system.MemoryUtil.memSet(dst, 0, 64);
        org.lwjgl.system.MemoryUtil.memPutFloat(dst,       1.0f);
        org.lwjgl.system.MemoryUtil.memPutFloat(dst + 20,  1.0f);
        org.lwjgl.system.MemoryUtil.memPutFloat(dst + 40,  1.0f);
        org.lwjgl.system.MemoryUtil.memPutFloat(dst + 60,  1.0f);
        uploadStream.commitFrame();
    }

    private void seedFillByte(me.cortex.vulkium.vk.DeviceBuffer buf, byte value) {
        long size = buf.size();
        // Chunk to the upload stream's section size (16 MB) so a big section buffer doesn't
        // overflow a single staging slice. For now both buffers are small (≤ 256 KB).
        long dst = uploadStream.upload(buf, 0L, (int) size);
        org.lwjgl.system.MemoryUtil.memSet(dst, value & 0xFF, size);
        uploadStream.commitFrame();
    }
}
