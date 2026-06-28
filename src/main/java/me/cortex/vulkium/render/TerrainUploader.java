package me.cortex.vulkium.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.vulkium.managers.BufferArena;
import me.cortex.vulkium.managers.SectionEntry;
import me.cortex.vulkium.managers.util.SegmentedManager;
import me.cortex.vulkium.vk.DeviceBuffer;
import me.cortex.vulkium.vk.UploadStream;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Streams captured per-section terrain geometry into a device-local {@link BufferArena}.
 *
 * <p>Sodium-edition: consumes {@link SectionEntry#sodiumLayers} (20-byte
 * {@code CompactChunkVertex} bytes already packed by Sodium's worker thread) and pass-through
 * memcopies them into the arena. No per-vertex transformation happens here — the matching
 * shader-side decode lives in {@code shaders/terrain/vertex_format.glsl}.
 *
 * <p>Allocation reuse: if a section was uploaded in a previous frame and the new quad count is
 * identical, we keep the old slot (no free / alloc) and just restream the bytes. Otherwise we
 * release the old slot and allocate fresh. This matches nvidium's churn pattern — most
 * re-uploads after a block edit land on the same quad count.
 *
 * <p>Failure policy: if {@link BufferArena#allocQuads(int)} returns
 * {@link SegmentedManager#SIZE_LIMIT} the arena is full; we surface that back to the caller
 * without uploading anything so the caller can decide between eviction and rejection. If
 * {@link UploadStream#upload} throws mid-upload after we've already allocated, we roll back
 * the allocation before rethrowing.
 *
 * <p>Not thread-safe — only touch on the render thread.
 */
public final class TerrainUploader implements AutoCloseable {

    /** Bytes per vertex in the vulkium arena. Matches Sodium's {@code CompactChunkVertex}
     *  (positionHi 4B + positionLo 4B + colour 4B + texCoord 4B + lightData 4B = 20). */
    public static final int VERTEX_STRIDE = 20;

    /** Fallback arena size if the config's {@code terrainArenaMb} is unreadable. 256 MB gives
     *  ~12.8M-quad capacity at 20B/vertex — enough headroom for a 32-chunk RD with per-section
     *  churn during block edits without hitting the SIZE_LIMIT branch. */
    private static final long FALLBACK_ARENA_SIZE = 256L << 20;

    private final BufferArena arena;

    /** sectionPosKey (SectionPos.asLong) → quad-address in the arena. */
    private final Long2IntOpenHashMap sectionToAddr = new Long2IntOpenHashMap();

    /** sectionPosKey → UNSORTED Sodium-format (20-byte/vert) translucent bytes, laid out as
     *  consecutive {@code qCount × 80B} quads in Sodium's build-order (no permutation
     *  applied). Kept so a future POV resort path can permute these without going back to
     *  Sodium's already-recycled NativeBuffer. Currently unused under Sodium — Sodium's own
     *  {@code ResortTransparencyTask} owns translucent sort; the corresponding MC-side mixin
     *  that fed {@link #resortTranslucent} never fires here. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<byte[]> translucentUnsortedCache =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** sectionPosKey → opaqueQuadCount at last upload. Needed to offset the translucent
     *  sub-range when resorting (arena slot layout is [opaque][translucent]). */
    private final Long2IntOpenHashMap sectionOpaqueQuads = new Long2IntOpenHashMap();

    private boolean closed;

    public TerrainUploader() {
        this.sectionToAddr.defaultReturnValue((int) SegmentedManager.SIZE_LIMIT);
        this.sectionOpaqueQuads.defaultReturnValue(-1);
        long arenaSize = FALLBACK_ARENA_SIZE;
        try {
            int mb = me.cortex.vulkium.VulkiumConfig.get().terrainArenaMb;
            if (mb > 0) arenaSize = (long) mb << 20;
        } catch (Throwable ignored) {
            // config not yet initialized — stick with fallback
        }
        this.arena = new BufferArena(arenaSize, VERTEX_STRIDE);
    }

    /** Out-parameter returned by {@link #uploadSectionSplit}: base quad address, split
     *  between opaque and translucent, and (when face binning is restored in Stage 2.5) the
     *  per-face quad counts. Arena layout:
     *  <pre>
     *    [addr                          ] opaque quads (currently all in unsigned bin)
     *    [addr + opaqueQuadCount        ] translucent quads
     *    [addr + opaqueQuadCount + translucentQuadCount) end
     *  </pre>
     *  Stage 2 ships with {@code faceBinCounts} all-zero — populateTasks reads this as
     *  "no per-face culling, walk the unsigned tail = entire opaque range". Restoring per-
     *  face culling is Stage 2.5; the data is already available via Sodium's
     *  {@link SectionEntry.SodiumLayerGeometry#vertexSegments}. */
    public static final class UploadResult {
        public final int addr;
        public final int opaqueQuadCount;
        public final int translucentQuadCount;
        public final int[] faceBinCounts;
        public UploadResult(int addr, int opaque, int translucent, int[] faceBinCounts) {
            this.addr = addr;
            this.opaqueQuadCount = opaque;
            this.translucentQuadCount = translucent;
            this.faceBinCounts = faceBinCounts;
        }
        public static final UploadResult FULL =
            new UploadResult((int) SegmentedManager.SIZE_LIMIT, 0, 0, new int[6]);
    }

    public int uploadSection(long sectionPosKey, SectionEntry entry, UploadStream stream) {
        return uploadSectionSplit(sectionPosKey, entry, stream).addr;
    }

    public UploadResult uploadSectionSplit(long sectionPosKey, SectionEntry entry, UploadStream stream) {
        if (closed) throw new IllegalStateException("TerrainUploader closed");

        // 1. Sum vertex count + byte total across all Sodium layers. Sodium provides the
        // vertex count via vertexSegments[] (sum of even-indexed slots, already pre-summed
        // into SodiumLayerGeometry.totalVertexCount on the worker thread). The byte total
        // is each layer's vertexBytes.remaining() — Sodium produces a contiguous tightly-
        // packed buffer with no trailing slack.
        int opaqueBytes = 0;
        int opaqueVerts = 0;
        int translucentBytes = 0;
        int translucentVerts = 0;
        SectionEntry.SodiumLayerGeometry translucentLayer = null;
        for (Map.Entry<TerrainRenderPass, SectionEntry.SodiumLayerGeometry> e : entry.sodiumLayers.entrySet()) {
            SectionEntry.SodiumLayerGeometry g = e.getValue();
            if (g == null || g.vertexBytes == null || g.totalVertexCount <= 0) continue;
            int bytes = g.vertexBytes.remaining();
            if (bytes <= 0) continue;
            if (e.getKey().isTranslucent()) {
                translucentBytes += bytes;
                translucentVerts += g.totalVertexCount;
                translucentLayer = g; // sodium emits at most one translucent pass per section
            } else {
                opaqueBytes += bytes;
                opaqueVerts += g.totalVertexCount;
            }
        }

        int totalVerts = opaqueVerts + translucentVerts;
        if (totalVerts == 0) {
            releaseSection(sectionPosKey);
            return UploadResult.FULL;
        }
        int quadCount = totalVerts / 4;
        if (quadCount == 0) {
            releaseSection(sectionPosKey);
            return UploadResult.FULL;
        }

        // 2. Reuse existing slot if the quad count matches, else alloc-then-free.
        // Critical ordering: allocate the new slot BEFORE freeing the old one. If we free
        // first and the arena is full, the subsequent alloc fails (SIZE_LIMIT) and we'd
        // return UploadResult.FULL leaving the section's GPU header.w pointing at the freed
        // slot — which another section will promptly reuse, scrambling the first section's
        // render. Keeping the old slot live when alloc fails preserves last-known-good
        // rendering until either memory frees up or the section is evicted.
        int existing = sectionToAddr.get(sectionPosKey);
        int addr;
        if (existing != (int) SegmentedManager.SIZE_LIMIT && arena.canReuse(existing, quadCount)) {
            addr = existing;
        } else {
            addr = arena.allocQuads(quadCount);
            if (addr == (int) SegmentedManager.SIZE_LIMIT) {
                return UploadResult.FULL;
            }
            if (existing != (int) SegmentedManager.SIZE_LIMIT) {
                arena.free(existing);
            }
        }

        // 3. Pass-through Sodium bytes into the arena.
        // Arena layout: [opaque_layer_0][opaque_layer_1]...[translucent] — Sodium guarantees
        // that within each layer, vertices are grouped by ModelQuadFacing (per vertexSegments),
        // so any all-unsigned-bin opaque dispatch will draw all 6 face directions in order.
        // Stage 2.5 will restore per-face bin dispatch by walking vertexSegments and
        // permuting facings into vulkium's task-shader bin order. For Stage 2 we leave
        // faceBinCounts zeroed → populateTasks treats everything as unsigned tail.
        long baseByteOffset = arena.byteOffsetOf(addr);
        long dstByteOffset = baseByteOffset;
        boolean newAlloc = existing != addr;
        try {
            // Opaque layers — concat in iteration order (SOLID first, then CUTOUT, or
            // whichever Sodium gave us). The mesh shader doesn't care about per-pass split
            // on the opaque side; alphaCutoff is per-render-pass, not per-vertex.
            for (Map.Entry<TerrainRenderPass, SectionEntry.SodiumLayerGeometry> e : entry.sodiumLayers.entrySet()) {
                if (e.getKey().isTranslucent()) continue;
                SectionEntry.SodiumLayerGeometry g = e.getValue();
                if (g == null || g.vertexBytes == null) continue;
                ByteBuffer src = g.vertexBytes;
                int bytes = src.remaining();
                if (bytes <= 0) continue;
                long dst = stream.upload(arena.buffer(), dstByteOffset, bytes);
                ByteBuffer dstBuf = MemoryUtil.memByteBuffer(dst, bytes);
                dstBuf.put(src.duplicate());
                dstByteOffset += bytes;
            }
            // Translucent layer — appended right after opaque so translucent draws index
            // [addr + opaqueQuads, addr + totalQuads). Sodium's build-order is whatever
            // ResortTransparencyTask hands it; we don't re-sort on this branch. Cache the
            // bytes for a potential future POV resort path.
            if (translucentLayer != null && translucentLayer.vertexBytes != null) {
                ByteBuffer src = translucentLayer.vertexBytes;
                int bytes = src.remaining();
                if (bytes > 0) {
                    long dst = stream.upload(arena.buffer(), dstByteOffset, bytes);
                    ByteBuffer dstBuf = MemoryUtil.memByteBuffer(dst, bytes);
                    dstBuf.put(src.duplicate());
                    dstByteOffset += bytes;

                    byte[] unsorted = new byte[bytes];
                    src.duplicate().get(unsorted);
                    translucentUnsortedCache.put(sectionPosKey, unsorted);
                }
            }
        } catch (RuntimeException ex) {
            if (newAlloc) {
                arena.free(addr);
            }
            sectionToAddr.remove(sectionPosKey);
            throw ex;
        }

        sectionToAddr.put(sectionPosKey, addr);
        sectionOpaqueQuads.put(sectionPosKey, opaqueVerts / 4);
        if (translucentVerts == 0) translucentUnsortedCache.remove(sectionPosKey);
        return new UploadResult(addr, opaqueVerts / 4, translucentVerts / 4, new int[6]);
    }

    /** Drop a section from the arena. Called when a section is evicted from the live table. */
    public void releaseSection(long sectionPosKey) {
        if (closed) return;
        int addr = sectionToAddr.remove(sectionPosKey);
        if (addr != (int) SegmentedManager.SIZE_LIMIT) {
            arena.free(addr);
        }
        translucentUnsortedCache.remove(sectionPosKey);
        sectionOpaqueQuads.remove(sectionPosKey);
    }

    /**
     * Legacy POV-resort path retained for source compat with {@code RenderSectionResortMixin}
     * (standalone-edition MC ingest). Under Sodium this method is dead code — MC's
     * {@code ResortTransparencyTask} doesn't run when Sodium owns chunk compilation, so the
     * upstream mixin never fires. Kept so {@link SectionManager#drainResorts} compiles; can
     * be deleted in Stage 6 cleanup along with the mixin.
     *
     * <p>If the cache miss bails early below — that's the normal path under Sodium.
     */
    public void resortTranslucent(long sectionPosKey, byte[] indexBytes, UploadStream stream) {
        if (closed) return;
        byte[] unsorted = translucentUnsortedCache.get(sectionPosKey);
        if (unsorted == null) return;
        int addr = sectionToAddr.get(sectionPosKey);
        if (addr == (int) SegmentedManager.SIZE_LIMIT) return;
        int opaqueQuads = sectionOpaqueQuads.get(sectionPosKey);
        if (opaqueQuads < 0) return;

        int qCount = unsorted.length / (4 * VERTEX_STRIDE);
        if (qCount == 0) return;
        int expectedIndexCount = qCount * 6;
        int bytesPerIdx = indexBytes.length / expectedIndexCount;
        if (bytesPerIdx != 2 && bytesPerIdx != 4) return;

        long translucentByteOffset = arena.byteOffsetOf(addr) + (long) opaqueQuads * 4L * VERTEX_STRIDE;
        int outBytes = qCount * 4 * VERTEX_STRIDE;
        long dstPtr = stream.upload(arena.buffer(), translucentByteOffset, outBytes);

        java.nio.ByteBuffer dstBuf = MemoryUtil.memByteBuffer(dstPtr, outBytes);
        int quadSize = 4 * VERTEX_STRIDE;
        java.nio.ByteBuffer ib = java.nio.ByteBuffer.wrap(indexBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < qCount; q++) {
            int idxOff = q * 6 * bytesPerIdx;
            int firstIdx = (bytesPerIdx == 2)
                    ? (ib.getShort(idxOff) & 0xFFFF)
                    : ib.getInt(idxOff);
            int origQuad = firstIdx >>> 2;
            if (origQuad < 0 || origQuad >= qCount) return;
            dstBuf.position(q * quadSize);
            dstBuf.put(unsorted, origQuad * quadSize, quadSize);
        }
    }

    /** The arena's backing {@link DeviceBuffer}; consumers plug its device address into the
     *  scene UBO's {@code terrainDataPtr}. */
    public DeviceBuffer arenaBuffer() {
        return arena.buffer();
    }

    /** For diagnostics / tests. */
    public BufferArena arena() {
        return arena;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        sectionToAddr.clear();
        arena.close();
    }
}
