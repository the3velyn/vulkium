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

    /**
     * 128 MB — enough for roughly 8 million quads, which comfortably covers a 16-chunk render
     * distance with margin for overdraw regions.
     */
    private static final long DEFAULT_ARENA_SIZE = 128L << 20;

    private final BufferArena arena;

    /** sectionPosKey (SectionPos.asLong) → quad-address in the arena. */
    private final Long2IntOpenHashMap sectionToAddr = new Long2IntOpenHashMap();

    private boolean closed;

    public TerrainUploader() {
        // SIZE_LIMIT == -1 is the sentinel returned by allocQuads when full; use it as the
        // Long2IntOpenHashMap "missing" default so a raw get() disambiguates naturally.
        this.sectionToAddr.defaultReturnValue((int) SegmentedManager.SIZE_LIMIT);
        this.arena = new BufferArena(DEFAULT_ARENA_SIZE, VERTEX_STRIDE);
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

        // 2. Reuse existing slot if the quad count matches, else free + re-alloc.
        int existing = sectionToAddr.get(sectionPosKey);
        int addr;
        if (existing != (int) SegmentedManager.SIZE_LIMIT && arena.canReuse(existing, quadCount)) {
            addr = existing;
        } else {
            if (existing != (int) SegmentedManager.SIZE_LIMIT) {
                arena.free(existing);
                sectionToAddr.remove(sectionPosKey);
            }
            addr = arena.allocQuads(quadCount);
            if (addr == (int) SegmentedManager.SIZE_LIMIT) {
                return UploadResult.FULL;
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
                repackMcToCompact(src, dstPtr, vCount);
                dstByteOffset += outBytes;
                opaqueVerts += vCount;
            }
            // Pass 2: translucent layer — appended right after opaque so translucent draws
            // index [addr + opaqueQuads, addr + totalQuads).
            SectionEntry.LayerGeometry trans = entry.layers.get(ChunkSectionLayer.TRANSLUCENT);
            if (trans != null && trans.vertexBytes != null && trans.vertexBytes.remaining() > 0
                    && trans.vertexCount > 0) {
                int vCount = trans.vertexCount;
                int outBytes = vCount * VERTEX_STRIDE;
                long dstPtr = stream.upload(arena.buffer(), dstByteOffset, outBytes);
                repackMcToCompact(trans.vertexBytes, dstPtr, vCount);
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
        return new UploadResult(addr, opaqueVerts / 4, translucentVerts / 4);
    }

    /** Drop a section from the arena. Called when a section is evicted from the live table. */
    public void releaseSection(long sectionPosKey) {
        if (closed) return;
        int addr = sectionToAddr.remove(sectionPosKey);
        if (addr != (int) SegmentedManager.SIZE_LIMIT) {
            arena.free(addr);
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
    private static void repackMcToCompact(ByteBuffer src, long dstPtr, int vCount) {
        // LE order matches Java's default and MC's vertex-buffer byte order.
        src = src.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int base = src.position();
        for (int v = 0; v < vCount; v++) {
            int o = base + v * MC_VERTEX_STRIDE;

            float px = src.getFloat(o);
            float py = src.getFloat(o + 4);
            float pz = src.getFloat(o + 8);

            int r = src.get(o + 12) & 0xFF;
            int g = src.get(o + 13) & 0xFF;
            int b = src.get(o + 14) & 0xFF;
            // alpha at o+15 ignored — nvidium's z.w is light, alpha comes from MIP bit in y.

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

            long p = dstPtr + (long) v * VERTEX_STRIDE;
            MemoryUtil.memPutInt(p,      pxQ | (pyQ << 16));
            // v.y high byte → block light (decodeLightUV.x in the shader).
            MemoryUtil.memPutInt(p + 4,  pzQ | (blockLight8 << 24));
            // v.z high byte → sky light (decodeLightUV.y in the shader).
            MemoryUtil.memPutInt(p + 8,  rgb | (skyLight8 << 24));
            MemoryUtil.memPutInt(p + 12, uQ | (vQ << 16));
        }
    }

    private static int clamp16(long v) {
        if (v < 0) return 0;
        if (v > 0xFFFF) return 0xFFFF;
        return (int) v;
    }
}
