package me.cortex.vulkium.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.vulkium.managers.BufferArena;
import me.cortex.vulkium.managers.SectionEntry;
import me.cortex.vulkium.managers.util.SegmentedManager;
import me.cortex.vulkium.vk.DeviceBuffer;
import me.cortex.vulkium.vk.UploadStream;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Streams captured per-section terrain geometry into a device-local {@link BufferArena}.
 *
 * <p>Lives on the render thread. The section-ingest drain pass walks freshly captured
 * {@link SectionEntry} instances and hands them here; we concatenate the per-layer vertex bytes
 * into a single contiguous arena slot, staged through {@link UploadStream}. Consumers plug
 * {@link #arenaBuffer()}'s device address into the scene UBO so mesh shaders can decode quads
 * from a quad-index + offset.
 *
 * <p>Allocation reuse: if a section was uploaded in a previous frame and the new quad count is
 * identical, we keep the old slot (no free / alloc) and just restream the bytes. Otherwise we
 * release the old slot and allocate fresh. This matches nvidium's churn pattern — most
 * re-uploads after a block edit land on the same quad count.
 *
 * <p>Failure policy: if {@link BufferArena#allocQuads(int)} returns {@link SegmentedManager#SIZE_LIMIT}
 * the arena is full; we surface that back to the caller without uploading anything so the caller
 * can decide between eviction and rejection. If {@link UploadStream#upload} throws mid-upload
 * after we've already allocated (and possibly partially staged earlier layers), we roll back the
 * allocation before rethrowing so the arena state stays consistent — the already-queued
 * {@link UploadStream} copies for prior layers will still fire on commit, but they'll write to a
 * now-free region that will be reallocated cleanly on the next upload. The map is wiped of this
 * key so a subsequent retry starts from a clean slate.
 *
 * <p>Not thread-safe — only touch on the render thread.
 */
public final class TerrainUploader implements AutoCloseable {

    /** Bytes per vertex in the vulkium arena. Matches scene.glsl's {@code Vertex = uvec4}. */
    public static final int VERTEX_STRIDE = 16;

    /** MC 26.2's chunk vertex stride: pos(12) + rgba(4) + uv(8) + uv2(4). */
    private static final int MC_VERTEX_STRIDE = 28;

    /** Nvidium model-space scale: position = packed * (32 / 65536) - 8. Inverse = 2048, offset 8. */
    private static final float POS_INV_SCALE = 65536f / 32f;   // = 2048
    private static final float POS_ORIGIN    = 8f;
    /** Nvidium texture UV scale: packed = uv * 32768. Matches TEXTURE_MAX_SCALE in vertex_format.glsl. */
    private static final float UV_SCALE      = 32768f;

    /** Fallback arena size if the config's {@code terrainArenaMb} is unreadable. 256 MB gives
     *  ~16M-quad capacity — enough headroom for a 32-chunk RD with per-section churn during
     *  block edits without hitting the SIZE_LIMIT branch. */
    private static final long FALLBACK_ARENA_SIZE = 256L << 20;

    private final BufferArena arena;

    /** sectionPosKey (SectionPos.asLong) → quad-address in the arena. */
    private final Long2IntOpenHashMap sectionToAddr = new Long2IntOpenHashMap();

    /** sectionPosKey → UNSORTED compact-format (16-byte/vert) translucent bytes, laid out as
     *  consecutive {@code qCount × 64B} quads in MC's build-order (no permutation applied).
     *  Needed so we can apply a fresh index-buffer sort later without going back to MC's
     *  already-freed MeshData.vertexBuffer — see {@link #resortTranslucent}. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<byte[]> translucentUnsortedCache =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** sectionPosKey → opaqueQuadCount at last upload. Needed to offset the translucent
     *  sub-range when resorting (arena slot layout is [opaque][translucent]). */
    private final Long2IntOpenHashMap sectionOpaqueQuads = new Long2IntOpenHashMap();

    private boolean closed;

    public TerrainUploader() {
        // SIZE_LIMIT == -1 is the sentinel returned by allocQuads when full; use it as the
        // Long2IntOpenHashMap "missing" default so a raw get() disambiguates naturally.
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

    /**
     * Upload a single captured section's geometry into the arena.
     *
     * <p>Sums the vertex bytes across all non-null layers (captures sometimes produce empty
     * layer entries), derives the quad count, reuses or (re)allocates a slot, and queues a
     * staged copy per layer via {@code stream}. The caller is responsible for
     * {@link UploadStream#commitFrame()} at end-of-frame — this method does not commit.
     *
     * @param sectionPosKey {@link net.minecraft.core.SectionPos#asLong} key
     * @param entry         captured per-layer geometry
     * @param stream        the current frame's upload stream
     * @return the quad-address in the arena, or {@link SegmentedManager#SIZE_LIMIT} if the arena
     *         is full (caller must evict or reject)
     */
    /** Out-parameter returned by {@link #uploadSection}: base quad address, the split between
     *  opaque and translucent, and — within the opaque range — the split across the 6
     *  axis-aligned face-direction bins plus an unsigned tail bin. Arena layout:
     *  <pre>
     *    [addr                                    ] bin0 (+X east face)
     *    [addr + bin[0]                           ] bin1 (+Y top)
     *    [addr + bin[0]+bin[1]                    ] bin2 (+Z south face)
     *    [addr + sum(bin[0..2])                   ] bin3 (-X west face)
     *    [addr + sum(bin[0..3])                   ] bin4 (-Y bottom)
     *    [addr + sum(bin[0..4])                   ] bin5 (-Z north face)
     *    [addr + sum(bin[0..5])                   ] unsigned tail (plants/cross-quads/non-aligned)
     *    [addr + opaqueQuadCount                  ] translucent
     *    [addr + opaqueQuadCount + translucentQuadCount) end
     *  </pre>
     *  The 6 face counts + the unsigned tail size together equal {@code opaqueQuadCount}. */
    public static final class UploadResult {
        public final int addr;
        public final int opaqueQuadCount;
        public final int translucentQuadCount;
        /** Per-face-direction quad counts, in task_common.glsl's populateTasks read order
         *  (which defines the bin semantics via the emit conditions):
         *  [0]=+X east, [1]=+Y top, [2]=+Z south, [3]=-X west, [4]=-Y bottom, [5]=-Z north.
         *  Unsigned tail size = opaqueQuadCount - sum(faceBinCounts). */
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

        // 1. Sum vertex count + byte total across all present layers. Use DrawState.vertexCount
        // as the source of truth (MC's MeshData guarantees it); bytes / verts then yields the
        // actual per-vertex stride the current MC version uses — not assumption-dependent.
        long totalVbBytes = 0L;
        int totalVerts = 0;
        for (Map.Entry<ChunkSectionLayer, SectionEntry.LayerGeometry> e : entry.layers.entrySet()) {
            SectionEntry.LayerGeometry geom = e.getValue();
            if (geom == null) continue;
            ByteBuffer vb = geom.vertexBytes;
            if (vb == null) continue;
            totalVbBytes += vb.remaining();
            totalVerts += geom.vertexCount;
        }

        if (totalVerts == 0) {
            // Empty section — release any prior allocation and stop.
            releaseSection(sectionPosKey);
            return UploadResult.FULL;
        }

        // Terrain is quad-indexed: 4 verts per quad. Round DOWN if MC occasionally produces a
        // stray non-quad vertex (shouldn't happen but don't crash).
        int quadCount = totalVerts / 4;
        if (quadCount == 0) {
            releaseSection(sectionPosKey);
            return UploadResult.FULL;
        }

        // 2. Reuse existing slot if the quad count matches, else alloc-then-free.
        // Critical ordering: allocate the new slot BEFORE freeing the old one. If we free
        // first and the arena is full, the subsequent alloc fails (SIZE_LIMIT) and we'd return
        // UploadResult.FULL leaving the section's GPU header.w pointing at the freed slot —
        // which another section will promptly reuse, scrambling the first section's render
        // (symptom: placing a block in a chunk that pushes the arena to full instantly hides
        // that chunk). Keeping the old slot live when alloc fails preserves last-known-good
        // rendering until either memory frees up or the section is evicted.
        int existing = sectionToAddr.get(sectionPosKey);
        int addr;
        if (existing != (int) SegmentedManager.SIZE_LIMIT && arena.canReuse(existing, quadCount)) {
            addr = existing;
        } else {
            addr = arena.allocQuads(quadCount);
            if (addr == (int) SegmentedManager.SIZE_LIMIT) {
                // Arena full — DO NOT free `existing`; keep the old slot valid so the GPU
                // keeps rendering stale-but-coherent geometry for this section.
                return UploadResult.FULL;
            }
            if (existing != (int) SegmentedManager.SIZE_LIMIT) {
                arena.free(existing);
            }
        }

        // 3. Stream per-layer bytes into the arena, repacking MC's 28-byte vertex layout into
        // nvidium's 16-byte compact uvec4 layout so scene.glsl's Vertex decode helpers work.
        // Source:  POS(3×float) + RGBA(4×byte) + UV(2×float) + UV2(2×short) = 28 bytes
        // Target:  v.x = posX16 | (posY16 << 16)
        //          v.y = posZ16 | (metadata << 16)   — metadata=0 for now
        //          v.z = RGB(24) | (blockLight << 24)
        //          v.w = U16 | (V16 << 16)                               = 16 bytes
        long baseByteOffset = arena.byteOffsetOf(addr);
        long dstByteOffset = baseByteOffset;
        boolean newAlloc = existing != addr;
        int opaqueVerts = 0;
        int translucentVerts = 0;
        int[] faceBinCounts = new int[6];
        try {
            // Pass 1: non-TRANSLUCENT opaque layers get face-direction binning. Each axis-aligned
            // quad is classified by its outward face normal (±X, ±Y, ±Z); non-axis-aligned
            // quads (plant cross-geometry, slab/stair slopes, non-block shapes) land in an
            // unsigned tail bin. Output order, matching task_common.glsl's populateTasks
            // bin read order (which defines bin semantics via the emit conditions):
            //   [+X east][+Y top][+Z south][-X west][-Y bottom][-Z north][unsigned]
            // This unlocks a big backface meshlet-style cull in the task shader: the 3 face
            // bins pointing AWAY from the camera get skipped and no mesh workgroups dispatch
            // for them — ~35% reduction in emitted opaque quads on typical scenes.
            //
            // Two-pass: classify quads across all non-translucent layers first (so we can
            // compute per-bin write cursors from the prefix sum), then repack into the right
            // slot. Per-quad cost is ~3 float comparisons + 1 cross-product component; small
            // next to the per-vertex arithmetic in packOneVertex.
            {
                int totalOpaqueQuads = 0;
                for (Map.Entry<ChunkSectionLayer, SectionEntry.LayerGeometry> e : entry.layers.entrySet()) {
                    if (e.getKey() == ChunkSectionLayer.TRANSLUCENT) continue;
                    SectionEntry.LayerGeometry geom = e.getValue();
                    if (geom == null) continue;
                    ByteBuffer src = geom.vertexBytes;
                    if (src == null || src.remaining() == 0) continue;
                    if (geom.vertexCount == 0) continue;
                    totalOpaqueQuads += geom.vertexCount / 4;
                }

                if (totalOpaqueQuads > 0) {
                    // Classify every opaque quad. Arrays hold per-quad source references +
                    // bin id. Cost: 4*N allocations but all primitive arrays (no GC pressure
                    // per quad, just one-shot scratch).
                    ByteBuffer[] qSrc = new ByteBuffer[totalOpaqueQuads];
                    int[] qOff = new int[totalOpaqueQuads];
                    int[] qCut = new int[totalOpaqueQuads];
                    byte[] qBin = new byte[totalOpaqueQuads];
                    int[] binCount = new int[7]; // 6 faces + unsigned
                    int qi = 0;
                    for (Map.Entry<ChunkSectionLayer, SectionEntry.LayerGeometry> e : entry.layers.entrySet()) {
                        if (e.getKey() == ChunkSectionLayer.TRANSLUCENT) continue;
                        SectionEntry.LayerGeometry geom = e.getValue();
                        if (geom == null) continue;
                        ByteBuffer src = geom.vertexBytes;
                        if (src == null || src.remaining() == 0) continue;
                        int vCount = geom.vertexCount;
                        if (vCount == 0) continue;
                        ByteBuffer le = src.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        int base = le.position();
                        int cutoff = cutoffBitsForLayer(e.getKey());
                        int layerQuads = vCount / 4;
                        for (int q = 0; q < layerQuads; q++) {
                            int v0Off = base + q * 4 * MC_VERTEX_STRIDE;
                            int bin = classifyQuadFace(le, v0Off);
                            qSrc[qi] = le;
                            qOff[qi] = v0Off;
                            qCut[qi] = cutoff;
                            qBin[qi] = (byte) bin;
                            binCount[bin]++;
                            qi++;
                        }
                    }
                    // Prefix sum → per-bin write cursor (in quads, relative to first opaque quad).
                    int[] binCursor = new int[7];
                    for (int i = 1; i < 7; i++) binCursor[i] = binCursor[i - 1] + binCount[i - 1];
                    // Stash final counts into the UploadResult view (unsigned tail = binCount[6]
                    // is derivable from opaqueQuadCount - sum(faceBinCounts)).
                    System.arraycopy(binCount, 0, faceBinCounts, 0, 6);

                    int opaqueBytes = totalOpaqueQuads * 4 * VERTEX_STRIDE;
                    long opaqueDstPtr = stream.upload(arena.buffer(), dstByteOffset, opaqueBytes);

                    // Emit each quad to its bin's current write slot.
                    for (int i = 0; i < totalOpaqueQuads; i++) {
                        int slot = binCursor[qBin[i]]++;
                        long quadDst = opaqueDstPtr + (long) slot * 4L * VERTEX_STRIDE;
                        ByteBuffer src = qSrc[i];
                        int off = qOff[i];
                        int cut = qCut[i];
                        for (int lane = 0; lane < 4; lane++) {
                            packOneVertex(src, off + lane * MC_VERTEX_STRIDE,
                                quadDst + (long) lane * VERTEX_STRIDE, cut);
                        }
                    }
                    dstByteOffset += opaqueBytes;
                    opaqueVerts += totalOpaqueQuads * 4;
                }
            }
            // Pass 2: translucent layer — appended right after opaque so translucent draws
            // index [addr + opaqueQuads, addr + totalQuads).
            //
            // Two-step:
            //   1. Always build an UNSORTED compact-byte array (repackMcToCompact) → cache by
            //      sectionKey. This is the "source of truth" for later POV resorts when MC's
            //      ResortTransparencyTask fires and our mixin hands us a new index buffer —
            //      we permute these cached bytes into the new order and re-upload to arena.
            //   2. If MC already handed us a build-time sort index buffer, apply it as a
            //      permutation to produce SORTED arena bytes; else memcpy the unsorted bytes.
            SectionEntry.LayerGeometry trans = entry.layers.get(ChunkSectionLayer.TRANSLUCENT);
            if (trans != null && trans.vertexBytes != null && trans.vertexBytes.remaining() > 0
                    && trans.vertexCount > 0) {
                int vCount = trans.vertexCount;
                int outBytes = vCount * VERTEX_STRIDE;
                int qCount = vCount / 4;

                // Step 1 — build unsorted cache via a temporary native scratch buffer.
                // TRANSLUCENT layer → cutoffBits=0 so the fragment shader doesn't discard
                // water/glass fragments. Encoded once here, then any later POV resort
                // (resortTranslucent) just permutes these already-packed bytes.
                final int transCutoff = cutoffBitsForLayer(ChunkSectionLayer.TRANSLUCENT);
                byte[] unsorted = new byte[outBytes];
                long tmp = MemoryUtil.nmemAlloc(outBytes);
                try {
                    repackMcToCompact(trans.vertexBytes, tmp, vCount, transCutoff);
                    MemoryUtil.memByteBuffer(tmp, outBytes).get(unsorted);
                } finally {
                    MemoryUtil.nmemFree(tmp);
                }
                translucentUnsortedCache.put(sectionPosKey, unsorted);

                // Step 2 — arena write. Prefer MC's build-time sort if present; else just
                // memcpy the already-built unsorted bytes from `unsorted` into the staging slot.
                long dstPtr = stream.upload(arena.buffer(), dstByteOffset, outBytes);
                ByteBuffer ib = trans.indexBytes;
                if (ib != null && trans.indexCount == qCount * 6 && ib.remaining() >= trans.indexCount * 2) {
                    int indexBytesPerElem = ib.remaining() / trans.indexCount;
                    repackMcToCompactSortedByIndex(trans.vertexBytes, ib, qCount,
                            indexBytesPerElem, dstPtr, transCutoff);
                } else {
                    MemoryUtil.memByteBuffer(dstPtr, outBytes).put(unsorted);
                }
                dstByteOffset += outBytes;
                translucentVerts += vCount;
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
     * Apply a POV-resorted translucent index buffer (produced by MC's
     * {@code ResortTransparencyTask}) to our already-cached unsorted translucent bytes, and
     * upload the re-permuted vertex data back to the arena's translucent sub-range.
     *
     * <p>{@code indexBytes} is MC's freshly-built sorted index buffer, 6 indices per quad.
     * For each sorted quad position q, {@code indexBytes[q*6*bytesPerIdx]} is {@code 4*k}
     * where {@code k} is the original (build-order) quad index. We copy that quad's 4
     * consecutive 16-byte verts from the cache to the arena at the new position q.
     *
     * <p>No-ops if the cache is missing (resort arrived before initial ingest, or section
     * was evicted). No-ops if quad counts don't line up.
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

        // Translucent quads live at [addr + opaqueQuads, addr + opaqueQuads + qCount) in the
        // arena, each quad = 4 verts × 16 B = 64 B.
        long translucentByteOffset = arena.byteOffsetOf(addr) + (long) opaqueQuads * 4L * VERTEX_STRIDE;
        int outBytes = qCount * 4 * VERTEX_STRIDE;
        long dstPtr = stream.upload(arena.buffer(), translucentByteOffset, outBytes);

        // Stream sorted quads into staging. indexBytes[q*6*bytesPerIdx] = 4 * originalQuad.
        java.nio.ByteBuffer dstBuf = MemoryUtil.memByteBuffer(dstPtr, outBytes);
        int quadSize = 4 * VERTEX_STRIDE;
        java.nio.ByteBuffer ib = java.nio.ByteBuffer.wrap(indexBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < qCount; q++) {
            int idxOff = q * 6 * bytesPerIdx;
            int firstIdx = (bytesPerIdx == 2)
                    ? (ib.getShort(idxOff) & 0xFFFF)
                    : ib.getInt(idxOff);
            int origQuad = firstIdx >>> 2;
            if (origQuad < 0 || origQuad >= qCount) return; // malformed index — bail
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

    /**
     * Repack {@code vCount} vertices from MC 26.2's 28-byte terrain format to nvidium's 16-byte
     * compact uvec4 format, writing directly to the native address {@code dstPtr}.
     *
     * <p>MC layout (LE):
     * <pre>
     *   off  0..11: posX, posY, posZ  (3 × float32, section-relative block coords 0..16)
     *   off 12..15: r, g, b, a        (4 × uint8)
     *   off 16..23: u, v              (2 × float32, atlas UV 0..1)
     *   off 24..27: blockLight, skyLight (2 × uint16, packed (light<<4)|0)
     * </pre>
     *
     * <p>Nvidium compact layout (scene.glsl {@code Vertex = uvec4}):
     * <pre>
     *   v.x: (posY16 << 16) | posX16                  [16-bit fixed, scale=2048, origin=8]
     *   v.y: (metadata << 16) | posZ16                [metadata=0 for now]
     *   v.z: (blockLight8 << 24) | rgb24
     *   v.w: (v16 << 16) | u16                         [16-bit fixed, scale=32768]
     * </pre>
     *
     * <p>Called on the render thread for every captured section layer; per-vertex cost ~50 ns
     * with bounds checks. A 2000-quad section repacks in ~400 µs. Acceptable; can be moved
     * to a compute-shader upload path later if it becomes hot.
     */
    private static void repackMcToCompact(ByteBuffer src, long dstPtr, int vCount, int cutoffBits) {
        // LE order matches Java's default and MC's vertex-buffer byte order.
        src = src.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int base = src.position();
        for (int v = 0; v < vCount; v++) {
            packOneVertex(src, base + v * MC_VERTEX_STRIDE, dstPtr + (long) v * VERTEX_STRIDE, cutoffBits);
        }
    }

    /** Layer → alphaCutoffIdx bits for the low 2 bits of the vertex-alpha byte.
     *
     * <p>Decoded by {@code terrain/vertex_format.glsl#rawVertexAlphaCutoff} into a cut
     * value the fragment shader uses as its discard threshold:
     * <ul>
     *   <li>SOLID → 0 → cut=0.0, no discard (opaque terrain).</li>
     *   <li>CUTOUT → 1 → cut=0.1, matches vanilla {@code RenderType.cutout} /
     *       {@code cutoutMipped}. Plants, leaves, iron bars, crafting table side, etc.
     *       Without this, mipmap-on tall grass averages its texel-0/1 alpha into
     *       ~50% values at distance, the {@code textureLod(..., 0)} sharp-alpha test
     *       in {@code terrain/frag.frag} only fires when {@code cut > 0}, and the
     *       fall-through {@code albedo.a <= 0.0} branch discards only zero alpha —
     *       so distant CUTOUT quads render as opaque blurry blocks instead of
     *       transparent-edged blades.</li>
     *   <li>TRANSLUCENT → 0 → cut=0.0, preserves blend. Water/glass/ice want
     *       tex.alpha × vertex.alpha, not a discard. The ealier
     *       "water renders as swiss-cheese speckles" regression came from reading
     *       cutoff bits straight out of MC's vertex-alpha byte, where the low 2 bits
     *       happened to flicker with animated-water alpha values and land on
     *       cutoffIdx=1 or 2. Gating on LAYER instead of vertex bits fixes both.</li>
     * </ul> */
    private static int cutoffBitsForLayer(ChunkSectionLayer layer) {
        // MC 26.2 has only SOLID / CUTOUT / TRANSLUCENT. Pre-26.2's CUTOUT_MIPPED merged
        // into CUTOUT; the mipmap-or-not distinction is now a sampler-state affair on the
        // atlas itself, not a layer selector. So we need exactly one cutoff-bearing value.
        return layer == ChunkSectionLayer.CUTOUT ? 1 : 0;
    }

    /**
     * Classify an opaque quad into a face-direction bin.
     *
     * <p>MC uses a right-handed Y-up coordinate system: {@code +X = east, +Y = up,
     * +Z = south}. Standard CCW-from-outside winding means the cross product
     * {@code (v1-v0) × (v2-v0)} points along the outward face normal.
     *
     * <p>Bin semantics are DEFINED BY task_common.glsl's populateTasks emit conditions.
     * Each bin is emitted when the camera sits on the side that can SEE the bin's
     * outward face direction. Getting these bin labels backwards is what produced the
     * "missing +X face quads when looking west" bug in the first draft — the labels
     * below are the canonical ones:
     *
     * <table>
     *   <tr><th>bin</th><th>task-shader condition</th><th>outward normal</th><th>face direction</th></tr>
     *   <tr><td>0</td><td>{@code relChunkPos.x <= 0} (camera east of section)</td><td>+X</td><td>east face</td></tr>
     *   <tr><td>1</td><td>{@code relChunkPos.y <= 0} (camera above section)</td><td>+Y</td><td>top</td></tr>
     *   <tr><td>2</td><td>{@code relChunkPos.z <= 0} (camera south of section)</td><td>+Z</td><td>south face</td></tr>
     *   <tr><td>3</td><td>{@code relChunkPos.x >= 0} (camera west of section)</td><td>-X</td><td>west face</td></tr>
     *   <tr><td>4</td><td>{@code relChunkPos.y >= 0} (camera below section)</td><td>-Y</td><td>bottom</td></tr>
     *   <tr><td>5</td><td>{@code relChunkPos.z >= 0} (camera north of section)</td><td>-Z</td><td>north face</td></tr>
     *   <tr><td>6</td><td>always</td><td>n/a</td><td>unsigned tail (plants, cross-geometry)</td></tr>
     * </table>
     *
     * <p>Classification: if all 4 quad corners share exactly one coordinate (X, Y, or Z),
     * the face is axis-aligned. The sign of the outward normal comes from the relevant
     * cross-product component. Non-aligned quads (plants, fences, stair slopes) fall into
     * bin 6 and render regardless of camera direction.
     *
     * <p>Why exact float equality works: MC quantizes block-face corners to whole or half
     * block positions stored as f32. A +X face of a block at column x has all 4 corners'
     * X component equal to x+1.0f exactly. Cross-geometry has corners at differing values
     * on every axis → falls through to bin 6.
     */
    private static int classifyQuadFace(ByteBuffer src, int v0Off) {
        int v1 = v0Off + MC_VERTEX_STRIDE;
        int v2 = v1 + MC_VERTEX_STRIDE;
        int v3 = v2 + MC_VERTEX_STRIDE;

        float x0 = src.getFloat(v0Off);
        float x1 = src.getFloat(v1);
        float x2 = src.getFloat(v2);
        float x3 = src.getFloat(v3);
        if (x0 == x1 && x1 == x2 && x2 == x3) {
            float y0 = src.getFloat(v0Off + 4);
            float y1 = src.getFloat(v1 + 4);
            float y2 = src.getFloat(v2 + 4);
            float z0 = src.getFloat(v0Off + 8);
            float z1 = src.getFloat(v1 + 8);
            float z2 = src.getFloat(v2 + 8);
            float nx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
            // nx > 0 → outward +X → bin 0 (east face). nx < 0 → -X → bin 3 (west face).
            return nx > 0f ? 0 : 3;
        }

        float y0 = src.getFloat(v0Off + 4);
        float y1 = src.getFloat(v1 + 4);
        float y2 = src.getFloat(v2 + 4);
        float y3 = src.getFloat(v3 + 4);
        if (y0 == y1 && y1 == y2 && y2 == y3) {
            float z0 = src.getFloat(v0Off + 8);
            float z1 = src.getFloat(v1 + 8);
            float z2 = src.getFloat(v2 + 8);
            float ny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
            // ny > 0 → outward +Y (top) → bin 1. ny < 0 → -Y (bottom) → bin 4.
            return ny > 0f ? 1 : 4;
        }

        float z0 = src.getFloat(v0Off + 8);
        float z1 = src.getFloat(v1 + 8);
        float z2 = src.getFloat(v2 + 8);
        float z3 = src.getFloat(v3 + 8);
        if (z0 == z1 && z1 == z2 && z2 == z3) {
            float nz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
            // nz > 0 → outward +Z (south) → bin 2. nz < 0 → -Z (north) → bin 5.
            return nz > 0f ? 2 : 5;
        }

        return 6; // unsigned tail (cross-geometry, rotated slopes, etc.)
    }

    /**
     * Translucent-sorted variant. MC's sort writes an index buffer with 6 indices per sorted
     * quad in the form {@code [4k+0, 4k+1, 4k+2, 4k+2, 4k+3, 4k+0]} where {@code k} walks the
     * BACK-TO-FRONT quad order for the build POV. We pull the leading index of each 6-tuple,
     * divide by 4 to recover the original quad index, and copy that quad's 4 verts to the
     * next slot in the destination — producing a vertex stream whose consecutive quad-sized
     * groups are already sorted. The mesh shader then needs no indirection: reading
     * {@code terrainData[(id<<2)+lane]} walks the sorted sequence.
     *
     * <p>Index element size = 2 (uint16) or 4 (uint32), selected by MC's
     * {@code VertexFormat.IndexType.least(vertexCount)}. We read it straight from the provided
     * {@code indexBytesPerElem} so we don't duplicate the decision.
     */
    private static void repackMcToCompactSortedByIndex(ByteBuffer vb, ByteBuffer ib,
                                                        int quadCount, int indexBytesPerElem,
                                                        long dstPtr, int cutoffBits) {
        vb = vb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ib = ib.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int vbBase = vb.position();
        int ibBase = ib.position();
        int ibStride = 6 * indexBytesPerElem;
        for (int q = 0; q < quadCount; q++) {
            int idxOff = ibBase + q * ibStride;
            int firstIdx = (indexBytesPerElem == 2)
                    ? (ib.getShort(idxOff) & 0xFFFF)
                    : ib.getInt(idxOff);
            // firstIdx == 4 * originalQuadIndex by MC's sortQuads layout.
            int origQuad = firstIdx >>> 2;
            int srcVertBase = origQuad << 2; // 4 verts per quad
            long dstVertBase = (long) (q << 2) * VERTEX_STRIDE;
            for (int lane = 0; lane < 4; lane++) {
                packOneVertex(vb,
                        vbBase + (srcVertBase + lane) * MC_VERTEX_STRIDE,
                        dstPtr + dstVertBase + (long) lane * VERTEX_STRIDE,
                        cutoffBits);
            }
        }
    }

    /** Shared per-vertex packer used by both linear and indexed repack paths.
     *
     *  <p>{@code cutoffBits} carries the layer-derived alphaCutoffIdx (see
     *  {@link #cutoffBitsForLayer}) — clobber MC's low 2 alpha bits with it so the fragment
     *  shader's discard threshold matches vanilla's per-layer rules without depending on
     *  whatever the vertex's alpha byte happened to be. Preserves top 6 bits of MC's alpha
     *  for the translucent blend multiplier. */
    private static void packOneVertex(ByteBuffer src, int o, long dstPtr, int cutoffBits) {
        float px = src.getFloat(o);
        float py = src.getFloat(o + 4);
        float pz = src.getFloat(o + 8);

        int r = src.get(o + 12) & 0xFF;
        int g = src.get(o + 13) & 0xFF;
        int b = src.get(o + 14) & 0xFF;
        // Alpha packing: top 6 bits = MC's vertex alpha (for translucent blend), low 2 bits
        // = layer-derived alphaCutoffIdx. Earlier revisions simply cleared the low 2 bits to
        // dodge a "water renders as swiss cheese" regression where cutoff bits came from
        // animated-water alpha values directly; that kept translucent safe but broke CUTOUT
        // (tall grass / leaves) because cutoffIdx was always 0 → no discard → mipped
        // albedo.a of ~0.3-0.7 passed fine → distant plants render as opaque boxes.
        // Gating on LAYER closes both failure modes.
        int a = (src.get(o + 15) & 0xFC) | (cutoffBits & 0x03);

        float u = src.getFloat(o + 16);
        float w = src.getFloat(o + 20);

        int bl = src.getShort(o + 24) & 0xFFFF;
        int sl = src.getShort(o + 26) & 0xFFFF;

        int pxQ = clamp16(Math.round((px + POS_ORIGIN) * POS_INV_SCALE));
        int pyQ = clamp16(Math.round((py + POS_ORIGIN) * POS_INV_SCALE));
        int pzQ = clamp16(Math.round((pz + POS_ORIGIN) * POS_INV_SCALE));
        int uQ  = clamp16(Math.round(u * UV_SCALE));
        int vQ  = clamp16(Math.round(w * UV_SCALE));

        int rgb = r | (g << 8) | (b << 16);
        // MC's vertex lightmap values are 0-240 in 16-step increments; sample_lightmap
        // formula is `clamp(uv/256.0 + 0.5/16.0, 0.5/16.0, 15.5/16.0)`. Our shader
        // applies the same formula, so pass bl/sl through raw (low 8 bits of each short).
        int blockLight8 = bl & 0xFF;
        int skyLight8   = sl & 0xFF;

        MemoryUtil.memPutInt(dstPtr,      pxQ | (pyQ << 16));
        // v.y: bits 0-15 = pzQ, bits 16-23 = vertex alpha (8-bit), bits 24-31 = block light.
        // The "metadata" nibble in the original nvidium format (alphaCutoff/mipping) lived
        // here too, but we don't populate those from MC's capture — the vertex alpha takes
        // precedence and the fragment shader reads it for translucent blending.
        MemoryUtil.memPutInt(dstPtr + 4,  pzQ | (a << 16) | (blockLight8 << 24));
        // v.z high byte → sky light (decodeLightUV.y in the shader).
        MemoryUtil.memPutInt(dstPtr + 8,  rgb | (skyLight8 << 24));
        MemoryUtil.memPutInt(dstPtr + 12, uQ | (vQ << 16));
    }

    private static int clamp16(long v) {
        if (v < 0) return 0;
        if (v > 0xFFFF) return 0xFFFF;
        return (int) v;
    }
}
