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
    private TranslucentSectionSorter translucentSorter;
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

        // MVP = projection * modelView. We use RenderSystem.getModelViewMatrixCopy() rather
        // than cam.viewRotationMatrix because the former is the matrix vanilla terrain was
        // rendered with — it has bobHurt/bobView/distortion (nausea) applied on top of the
        // rotation. Without it, vulkium terrain is rigid while entities/particles bob around.
        Matrix4f modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy();
        Matrix4f mvp = new Matrix4f(cam.projectionMatrix).mul(modelView);

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
        // Translucent back-to-front section sort — CPU builds a per-frame list the translucent
        // task shader indexes in place of gl_WorkGroupID.x. Far sections dispatch first so
        // their blended pixels land before closer sections blend over them.
        long translucentSortPtr = 0L;
        if (translucentSorter != null) {
            me.cortex.vulkium.managers.SectionManager smgr = me.cortex.vulkium.managers.SectionManager.get();
            translucentSorter.sort(smgr.liveView(), rm, smgr, cx, cy, cz);
            translucentSortPtr = translucentSorter.deviceAddress();
        }
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
            // nvidium convention: payload.origin + decodeVertexPosition give positions in
            // (X, Z, Y) order. subchunkOffset must also be in that order so the subtraction in
            // transformVertex stays axis-consistent before the Y/Z swap at MVP multiplication.
            .subchunkOffset(fx, fz, fy, 0.0f)
            .fogColour(0, 0, 0, 0)
            // Buffer-reference pointers populated as each subsystem lands its buffers.
            .regionIndicesPtr(0L)
            .regionDataPtr(regionPtr)
            .sectionDataPtr(sectionPtr)
            .regionVisibilityPtr(regionVisibilityBuffer != null ? regionVisibilityBuffer.deviceAddress() : 0L)
            .sectionVisibilityPtr(sectionVisibilityBuffer != null ? sectionVisibilityBuffer.deviceAddress() : 0L)
            .terrainCmdPtr(0L)
            .translucencyCmdPtr(0L)
            // Repurposed: this pointer now carries the translucent-section sort list for the
            // translucent task shader (far-to-near section IDs as uint16). RegionSorter's
            // region-level list isn't wired into any draw path yet.
            .sortingRegionListPtr(translucentSortPtr)
            .terrainDataPtr(terrainPtr)
            .transformationArrPtr(transformationBuffer != null ? transformationBuffer.deviceAddress() : 0L)
            .originArrPtr(originBuffer != null ? originBuffer.deviceAddress() : 0L)
            .statisticsPtr(0L)
            // nvidium convention: screenSize is HALF the framebuffer resolution in pixels.
            // Mesh shader bbox cull does `((pos.xy/pos.w)+1) * screenSize` → NDC [-1..1] +1 = [0..2]
            // then × (W/2, H/2) = [0..W, 0..H] pixel coords. Use MC's window — MojangColorFormat
            // can read 0 early in the frame before ChunkSectionsToRender's first render-group fires.
            .screenSize(
                net.minecraft.client.Minecraft.getInstance().getWindow().getWidth()  * 0.5f,
                net.minecraft.client.Minecraft.getInstance().getWindow().getHeight() * 0.5f)
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
            translucentSorter = new TranslucentSectionSorter(
                me.cortex.vulkium.VulkiumConfig.get().maxRegions);
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
            // VMA-allocated device-local memory is UNDEFINED contents, not zeroed — we must
            // explicitly fill. Otherwise unpackOriginOffsetId reads random bits and produces
            // wildly large signed offsets → payload.origin ends up tens of thousands off →
            // every mesh-transformed vertex clips out of view.
            originBuffer = me.cortex.vulkium.vk.DeviceBuffer.allocate(
                (long) transformationCount * 8L);
            seedFillByte(originBuffer, (byte) 0x00);

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
        }
        if (translucentSorter != null) {
            try { translucentSorter.close(); } catch (Throwable t) { LOGGER.warn("TranslucentSectionSorter close failed", t); }
            translucentSorter = null;
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
        // Fill every transformation-array slot with identity, not just slot 0. Task shaders pick
        // transformationId per region from regionData — until we actually drive per-region
        // transforms, every index should still give a valid identity matrix. Slots left zeroed
        // produce degenerate transforms and invisible geometry.
        long size = buf.size();
        int slots = (int) (size / 64L);
        long dst = uploadStream.upload(buf, 0L, (int) size);
        org.lwjgl.system.MemoryUtil.memSet(dst, 0, size);
        for (int i = 0; i < slots; i++) {
            long p = dst + (long) i * 64L;
            org.lwjgl.system.MemoryUtil.memPutFloat(p,       1.0f);
            org.lwjgl.system.MemoryUtil.memPutFloat(p + 20,  1.0f);
            org.lwjgl.system.MemoryUtil.memPutFloat(p + 40,  1.0f);
            org.lwjgl.system.MemoryUtil.memPutFloat(p + 60,  1.0f);
        }
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
