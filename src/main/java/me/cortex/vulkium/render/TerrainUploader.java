package me.cortex.vulkium.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.vulkium.managers.BufferArena;
import me.cortex.vulkium.managers.SectionEntry;
import me.cortex.vulkium.managers.util.SegmentedManager;
import me.cortex.vulkium.vk.DeviceBuffer;
import me.cortex.vulkium.vk.UploadStream;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

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
        // Iterate in DefaultTerrainRenderPasses.ALL order (SOLID, CUTOUT, TRANSLUCENT) — the
        // sodiumLayers map is a HashMap with non-deterministic iteration order, which under
        // certain JVM hash bucketings put CUTOUT before SOLID. That orders CUTOUT bytes
        // ahead of SOLID bytes within a face bin, and the mesh shader emits in that order.
        // With reverse-Z depth-compare GREATER_OR_EQUAL, the LATER-rendered quad wins ties:
        // grass overlay quads (CUTOUT) sit at the SAME depth as the dirt face (SOLID), so
        // when CUTOUT emitted first then SOLID drawn over it, dirt won the tie and the green
        // overlay never reached the framebuffer. Symptom: grass sides showed plain untinted
        // dirt (vertex.colour = white instead of biomeGreen).
        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            SectionEntry.SodiumLayerGeometry g = entry.sodiumLayers.get(pass);
            if (g == null || g.vertexBytes == null || g.totalVertexCount <= 0) continue;
            int bytes = g.vertexBytes.remaining();
            if (bytes <= 0) continue;
            if (pass.isTranslucent()) {
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
            // Reclaim the tail when the new quad count is smaller than the slot — without
            // this, shrink-reuse leaves dead bytes inside the slot that compound across
            // many rebuilds (block edits varying geometry) and progressively bloats the
            // arena. shrink() is a no-op when newQuads == existing slot size.
            arena.shrink(addr, quadCount);
        } else {
            addr = arena.allocQuads(quadCount);
            if (addr == (int) SegmentedManager.SIZE_LIMIT) {
                return UploadResult.FULL;
            }
            if (existing != (int) SegmentedManager.SIZE_LIMIT) {
                arena.free(existing);
            }
        }

        // 3. Pass-through Sodium bytes into the arena, reordered by face direction.
        // Arena layout per section: [opaque face-binned][translucent].
        //
        // Within the opaque region we permute Sodium's vertex layout (which groups quads
        // by ModelQuadFacing per layer) into vulkium's task-shader bin order:
        //   bin 0 = +X (east)      = sodium POS_X (ordinal 0)
        //   bin 1 = +Z (south)     = sodium POS_Z (ordinal 2)
        //   bin 2 = +Y (top)       = sodium POS_Y (ordinal 1)
        //   bin 3 = -X (west)      = sodium NEG_X (ordinal 3)
        //   bin 4 = -Z (north)     = sodium NEG_Z (ordinal 5)
        //   bin 5 = -Y (bottom)    = sodium NEG_Y (ordinal 4)
        //   tail  = unsigned       = sodium UNASSIGNED (ordinal 6)
        // populateTasks (task_common.glsl) reads faceBinCounts[0..5] and emits only the
        // three bins whose outward normal faces the camera + the tail bin → ~35-50%
        // fewer mesh workgroups dispatched on typical outdoor scenes.
        //
        // We DELIBERATELY merge SOLID and CUTOUT into one face-binned blob. The 2-bit
        // alphaCutoff is per-vertex on Sodium (encoded in the lightData material byte),
        // so the mesh+frag stages can distinguish layers without us keeping them split.
        long baseByteOffset = arena.byteOffsetOf(addr);
        long dstByteOffset = baseByteOffset;
        boolean newAlloc = existing != addr;
        int[] faceBinCounts = new int[6];
        try {
            // Walk all opaque layers' vertexSegments to accumulate per-sodium-facing vert
            // counts. Sodium emits at most one segment per (layer, facing) pair (see
            // ChunkBuildBuffers.createMesh) but we tolerate duplicates defensively.
            int[] sodiumPerFacingVerts = new int[ModelQuadFacingCount];
            int sodiumTotalOpaqueVerts = 0;
            for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
                if (pass.isTranslucent()) continue;
                SectionEntry.SodiumLayerGeometry g = entry.sodiumLayers.get(pass);
                if (g == null || g.vertexSegments == null) continue;
                int[] segs = g.vertexSegments;
                for (int i = 0; i + 1 < segs.length; i += 2) {
                    int cnt = segs[i];
                    if (cnt <= 0) continue;
                    int facing = segs[i + 1];
                    if (facing < 0 || facing >= ModelQuadFacingCount) continue;
                    sodiumPerFacingVerts[facing] += cnt;
                    sodiumTotalOpaqueVerts += cnt;
                }
            }
            // Per-vulkium-bin quad counts (NOT including unsigned tail — that's implied by
            // total - sum(bins)). Vertices to quads = /4 (Sodium emits 4 verts/quad).
            for (int bin = 0; bin < 6; bin++) {
                faceBinCounts[bin] = sodiumPerFacingVerts[VULKIUM_BIN_TO_SODIUM_FACING[bin]] / 4;
            }

            int totalOpaqueBytes = sodiumTotalOpaqueVerts * VERTEX_STRIDE;
            if (totalOpaqueBytes > 0) {
                long opaqueDstPtr = stream.upload(arena.buffer(), dstByteOffset, totalOpaqueBytes);
                long writeCursor = 0L;
                // Emit in vulkium order: bins 0-5, then UNASSIGNED tail.
                // Layer order WITHIN a bin matters: SOLID must come before CUTOUT so the
                // mesh shader emits dirt-style quads first and CUTOUT overlay quads after.
                // With reverse-Z GREATER_OR_EQUAL, later-emitted wins ties — overlay
                // (CUTOUT) on top of dirt (SOLID) at the same depth needs that ordering or
                // dirt wins and biome-tinted overlays disappear (grass-side symptom).
                for (int targetFacing : VULKIUM_EMIT_ORDER) {
                    for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
                        if (pass.isTranslucent()) continue;
                        SectionEntry.SodiumLayerGeometry g = entry.sodiumLayers.get(pass);
                        if (g == null || g.vertexBytes == null || g.vertexSegments == null) continue;
                        long srcBaseAddr = MemoryUtil.memAddress(g.vertexBytes);
                        int[] segs = g.vertexSegments;
                        long layerSrcOff = 0L;
                        for (int i = 0; i + 1 < segs.length; i += 2) {
                            int cnt = segs[i];
                            int segBytes = cnt * VERTEX_STRIDE;
                            if (cnt > 0 && segs[i + 1] == targetFacing) {
                                MemoryUtil.memCopy(srcBaseAddr + layerSrcOff,
                                                   opaqueDstPtr + writeCursor, segBytes);
                                writeCursor += segBytes;
                            }
                            layerSrcOff += segBytes;
                        }
                    }
                }
                dstByteOffset += totalOpaqueBytes;
                opaqueVerts = sodiumTotalOpaqueVerts;
            } else {
                opaqueVerts = 0;
            }

            // Translucent layer — appended after the opaque face-binned region. Translucent
            // doesn't get per-face culling (alpha-blended geometry needs every quad drawn);
            // Sodium's translucent buffer is already sorted by its own ResortTransparencyTask
            // when applicable. Cache the bytes for a potential future POV resort path.
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
        return new UploadResult(addr, opaqueVerts / 4, translucentVerts / 4, faceBinCounts);
    }

    /** Number of {@code ModelQuadFacing} values, including UNASSIGNED. */
    private static final int ModelQuadFacingCount = 7;

    /** vulkium task-shader bin index → sodium {@code ModelQuadFacing} ordinal. The 6 face
     *  bins; UNASSIGNED is handled separately as the tail. */
    private static final int[] VULKIUM_BIN_TO_SODIUM_FACING = {
        0, // bin 0 (+X east)    ← POS_X
        2, // bin 1 (+Z south)   ← POS_Z
        1, // bin 2 (+Y top)     ← POS_Y
        3, // bin 3 (-X west)    ← NEG_X
        5, // bin 4 (-Z north)   ← NEG_Z
        4  // bin 5 (-Y bottom)  ← NEG_Y
    };

    /** Sodium facing ordinals in the order we emit them into the arena. Bins 0-5 in
     *  vulkium order, then UNASSIGNED as the tail. */
    private static final int[] VULKIUM_EMIT_ORDER = {0, 2, 1, 3, 5, 4, 6};

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
