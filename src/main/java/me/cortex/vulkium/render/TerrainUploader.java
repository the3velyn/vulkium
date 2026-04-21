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
    /** Out-parameter returned by {@link #uploadSection}: base quad address plus the split
     *  between opaque and translucent quads in that range. Opaque quads occupy [addr, addr+opaque),
     *  translucent quads occupy [addr+opaque, addr+opaque+translucent). */
    public static final class UploadResult {
        public final int addr;
        public final int opaqueQuadCount;
        public final int translucentQuadCount;
        public UploadResult(int addr, int opaque, int translucent) {
            this.addr = addr;
            this.opaqueQuadCount = opaque;
            this.translucentQuadCount = translucent;
        }
        public static final UploadResult FULL =
            new UploadResult((int) SegmentedManager.SIZE_LIMIT, 0, 0);
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
        try {
            // Pass 1: non-TRANSLUCENT layers first — they land at `addr`.
            for (Map.Entry<ChunkSectionLayer, SectionEntry.LayerGeometry> e : entry.layers.entrySet()) {
                if (e.getKey() == ChunkSectionLayer.TRANSLUCENT) continue;
                SectionEntry.LayerGeometry geom = e.getValue();
                if (geom == null) continue;
                ByteBuffer src = geom.vertexBytes;
                if (src == null || src.remaining() == 0) continue;
                int vCount = geom.vertexCount;
                if (vCount == 0) continue;

                int outBytes = vCount * VERTEX_STRIDE;
                long dstPtr = stream.upload(arena.buffer(), dstByteOffset, outBytes);
                repackMcToCompact(src, dstPtr, vCount, cutoffBitsForLayer(e.getKey()));
                dstByteOffset += outBytes;
                opaqueVerts += vCount;
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
        return new UploadResult(addr, opaqueVerts / 4, translucentVerts / 4);
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
