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
    private PrimaryTerrainPass primaryTerrain;
    private TerrainUploader terrainUploader;
    private UploadStream uploadStream;
    private HzbBuilder hzbBuilder;
    private HzbTexture hzbTexture;
    private RegionCuller regionCuller;
    /** True if a previous frame ran region_cull; used to decide when to re-seed
     *  {@link #regionVisibilityBuffer} back to all-0xFF after the user toggles cull off. */
    private boolean lastFrameRanCull;
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
    private OpaqueDispatchList opaqueDispatchList;
    private int hzbWidth;
    private int hzbHeight;
    private long hzbLastBuildNs;
    private boolean initFailed;

    /** Upload-ring section size (bytes) × count. Balances per-frame peak upload vs memory. */
    private static final long UPLOAD_SECTION_BYTES = 16L * 1024 * 1024;   // 16 MB / frame peak
    // Bump ring to 6 slices so heavy ingest bursts (render-distance change, F3+A) don't hit the
    // mid-frame wraparound path in UploadStream nearly as often. The mid-frame commit in
    // advanceSection is correct but involves a submit + flush, which is measurable overhead.
    private static final int  UPLOAD_SECTION_COUNT = 6;

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

        // MVP = projection * viewRotation. Rotation-only matrix pairs with our in-shader
        // chunkPosition subtraction; using a full modelView (with camera translation baked in)
        // double-offsets positions. FrameDriver.onEndMain re-refreshes this value later in the
        // frame using the same rotation-only matrix.
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
        // Translucent section sort: CPU-side back-to-front, writes GPU-compact section IDs
        // directly into a host-mapped BDA staging buffer (no UploadStream round-trip, so the
        // upload ring stays clean for chunk-mesh uploads). The shader picks this list up via
        // sortingRegionListPtr and redirects gl_WorkGroupID.x through it.
        long translucentSortPtr = 0L;
        me.cortex.vulkium.config.TranslucencySortingLevel sortLevel =
            me.cortex.vulkium.VulkiumConfig.get().translucencySortingLevel;
        // NONE = skip cross-section sort entirely; SECTIONS/QUADS both compute the back-to-front
        // order (QUADS additionally applies MC's POV resort inside each section via drainResorts).
        if (translucentSorter != null && rm != null
                && sortLevel != me.cortex.vulkium.config.TranslucencySortingLevel.NONE) {
            long tTs = me.cortex.vulkium.diag.PerfTracker.begin();
            translucentSorter.sort(
                me.cortex.vulkium.managers.SectionManager.get().translucentSectionKeys(),
                rm,
                me.cortex.vulkium.managers.SectionManager.get(),
                cx, cy, cz);
            me.cortex.vulkium.diag.PerfTracker.end("translucentSort", tTs);
            translucentSortPtr = translucentSorter.deviceAddress();
        }

        // Section-keep-distance sweep. Runs every 60 frames to amortize the live-map walk.
        // 256+ = keep all (no-op); any other value (including 32 = "Vanilla") actually sweeps.
        // Vulkium doesn't inherit MC's unload path — the RenderSectionMixin deliberately skips
        // eviction on the rotating-cache reassignment — so 32 = "Vanilla-ish" means *this*
        // sweep runs at radius 32, not "let MC do it".
        int keepDist = me.cortex.vulkium.VulkiumConfig.get().regionKeepDistance;
        if (keepDist < 256 && (FrameDriver.frameCount() % 60L) == 0L) {
            me.cortex.vulkium.managers.SectionManager.get()
                .sweepKeepDistance(cx, cz, keepDist, 256);
        }
        // Region-level frustum + distance cull. Uses MC's cullFrustum directly — no sodium dep.
        // getEffectiveRenderDistance() gives the server-clamped effective value (raw slider on
        // SP, min(slider, serverRenderDistance) on MP), which is exactly what we want — vulkium
        // doesn't need to cull further out than MC is actually loading chunks.
        if (cam.cullFrustum != null && rm != null) {
            int rd = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance();
            long tVis = me.cortex.vulkium.diag.PerfTracker.begin();
            visibility.update(cam.cullFrustum, cx, cy, cz, rd);
            me.cortex.vulkium.diag.PerfTracker.end("visibility.update", tVis);
        }

        if (opaqueDispatchList != null && rm != null) {
            long tOp = me.cortex.vulkium.diag.PerfTracker.begin();
            opaqueDispatchList.build(
                me.cortex.vulkium.managers.SectionManager.get(),
                rm, visibility);
            me.cortex.vulkium.diag.PerfTracker.end("opaqueList.build", tOp);
        }

        // Drain dirty regions into the GPU-side regionBuffer + sectionBuffer. SectionManager
        // marks regions dirty whenever it populates a section header — without this upload
        // step our CPU-side writes never reach shader visibility.
        long tDirty = me.cortex.vulkium.diag.PerfTracker.begin();
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
        me.cortex.vulkium.diag.PerfTracker.end("drainDirty", tDirty);

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
            // Repurposed from the nvidium-era region sorter: this pointer now carries the
            // translucent-section sort list (far-to-near GPU-compact section IDs) for the
            // translucent task shader's gl_WorkGroupID.x redirect.
            .sortingRegionListPtr(translucentSortPtr)
            .terrainDataPtr(terrainPtr)
            .transformationArrPtr(transformationBuffer != null ? transformationBuffer.deviceAddress() : 0L)
            .originArrPtr(originBuffer != null ? originBuffer.deviceAddress() : 0L)
            .statisticsPtr(0L)
            .opaqueDispatchListPtr(opaqueDispatchList != null ? opaqueDispatchList.deviceAddress() : 0L)
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

        // Submit staging copies NOW (at START_MAIN), not at AFTER_OPAQUE_TERRAIN. This gives
        // the GPU MC's sky / opaque-terrain / entities / clouds rendering window to chew
        // through our arena + region/section-header uploads in parallel, so by the time our
        // dispatch records at AFTER_OPAQUE_TERRAIN the copies are already in flight or done.
        if (uploadStream != null) {
            try {
                long tCommit = me.cortex.vulkium.diag.PerfTracker.begin();
                uploadStream.commitFrame();
                me.cortex.vulkium.diag.PerfTracker.end("uploadCommit", tCommit);
            } catch (Throwable t) {
                LOGGER.warn("UploadStream.commitFrame failed (prepareFrame)", t);
            }
        }
    }

    public SceneUniform sceneUniform() { return sceneUniform; }
    public VisibilityTracker visibility() { return visibility; }
    public PrimaryTerrainPass primaryTerrain() { return primaryTerrain; }
    public OpaqueDispatchList opaqueDispatchList() { return opaqueDispatchList; }
    public TranslucentSectionSorter translucentSorter() { return translucentSorter; }
    public TerrainUploader terrainUploader() { return terrainUploader; }
    public UploadStream uploadStream() { return uploadStream; }

    /** Public gate for FrameDriver — initializes the renderer eagerly on first frame even
     *  when drawTerrain / enableHzb / F3-overlay are all off. Without this, drainPending
     *  silently drops the first batch of compiled sections because {@code terrainUploader}
     *  hasn't been created yet, and those sections never come back until MC re-ingests
     *  them (via a block edit or F3+A). */
    public void ensureReady() {
        ensureInit();
    }
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

    /**
     * Dispatch the region-cull compute shader. Called from {@code FrameDriver} at
     * {@code AFTER_OPAQUE_TERRAIN}, BEFORE the vulkium opaque draw. Uses the HZB built at
     * the end of the previous frame (see {@link #buildHzb}).
     *
     * <p>No-op when (a) cull is disabled in config, (b) HZB doesn't exist yet (first frame
     * post-enable), or (c) there are no live regions to cull. The task shader's new gate on
     * {@code regionVisibility} will see the all-0xFF boot seed in those cases and treat every
     * region as visible — identical to pre-P0-2 behavior.
     *
     * <p>Also handles the cull-toggled-off transition: if cull was running last frame and is
     * off this frame, re-seed {@code regionVisibilityBuffer} to all-0xFF so residual zeros
     * from the prior enabled window don't stick.
     */
    public void runRegionCull() {
        if (initFailed) return;
        me.cortex.vulkium.managers.RegionManager rm = me.cortex.vulkium.Vulkium.regionManager();
        boolean configOn = VulkiumConfig.get().enableHzbRegionCull;
        boolean canRun = configOn && regionCuller != null && hzbTexture != null
            && sceneUniform != null && rm != null && rm.regionCount() > 0;

        if (!canRun) {
            if (lastFrameRanCull) {
                // Transitioning OFF: wipe residual zeros written by prior frames so the task
                // shader's regionVisibility gate stops culling geometry that's actually visible.
                if (regionVisibilityBuffer != null && uploadStream != null) {
                    try {
                        seedFillByte(regionVisibilityBuffer, (byte) 0xFF);
                    } catch (Throwable t) {
                        LOGGER.warn("Region-visibility reseed failed on cull toggle-off", t);
                    }
                }
                lastFrameRanCull = false;
            }
            return;
        }

        final RegionCuller culler = regionCuller;
        final SceneUniform scene = sceneUniform;
        final HzbTexture hzb = hzbTexture;
        // Dispatch over [0, maxRegionIndex): every allocated region ID falls in this range.
        // idProvider recycles released IDs so live slots can be anywhere in this span — we
        // must cull them all, not just map.size() threads' worth (that would skip the high
        // slots on a sparse ledger).
        final int upperBound = rm.maxRegionIndex();
        if (upperBound <= 0) return;
        try {
            CommandRecorder.recordAndSubmit(cmd -> culler.record(cmd, scene, hzb, upperBound));
            lastFrameRanCull = true;
        } catch (Throwable t) {
            LOGGER.warn("Region-cull dispatch failed (continuing without occlusion this frame)", t);
        }
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
            translucentSorter = new TranslucentSectionSorter(
                me.cortex.vulkium.VulkiumConfig.get().maxRegions);
            opaqueDispatchList = new OpaqueDispatchList(
                me.cortex.vulkium.VulkiumConfig.get().maxRegions);
            primaryTerrain = new PrimaryTerrainPass();
            terrainUploader = new TerrainUploader();
            regionCuller = new RegionCuller();

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
                    + "PrimaryTerrainPass + TerrainUploader({}MB arena) + "
                    + "transformationBuffer(identity) + originBuffer.",
                SceneUniform.SCENE_UBO_SIZE, UPLOAD_SECTION_BYTES / (1024 * 1024), UPLOAD_SECTION_COUNT,
                terrainUploader.arena().allocatedMB());
        } catch (Throwable t) {
            LOGGER.error("Renderer init failed (marking as failed, vulkium rendering paths will no-op)", t);
            initFailed = true;
        }
    }

    public void shutdown() {
        // Close in reverse construction order so dependent VK handles teardown before their
        // predecessors (pipelines hold references to modules + VkDevice; uploader holds its
        // arena's DeviceBuffer; UploadStream holds a StagingBuffer).
        if (regionCuller != null) {
            try { regionCuller.close(); } catch (Throwable t) { LOGGER.warn("RegionCuller close failed", t); }
            regionCuller = null;
        }
        if (hzbTexture != null) {
            try { hzbTexture.close(); } catch (Throwable t) { LOGGER.warn("HzbTexture close failed", t); }
            hzbTexture = null;
        }
        if (hzbBuilder != null) {
            try { hzbBuilder.close(); } catch (Throwable t) { LOGGER.warn("HzbBuilder close failed", t); }
            hzbBuilder = null;
        }
        lastFrameRanCull = false;
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
        if (translucentSorter != null) {
            try { translucentSorter.close(); } catch (Throwable t) { LOGGER.warn("TranslucentSectionSorter close failed", t); }
            translucentSorter = null;
        }
        if (opaqueDispatchList != null) {
            try { opaqueDispatchList.close(); } catch (Throwable t) { LOGGER.warn("OpaqueDispatchList close failed", t); }
            opaqueDispatchList = null;
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
