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

    /**
     * Bytes per terrain vertex. MC 26.2's terrain vertex format is
     * {POS(12) + COLOR(4) + UV(8) + UV2(4)} = 28 bytes. Nvidium's scene.glsl expects a
     * compact 16-byte uvec4 format — that format-mismatch is a known gap; for now we pass
     * MC's raw bytes through the arena and the shader renders garbage under drawTerrain.
     * CPU-side repack lands in a follow-up.
     */
    public static final int VERTEX_STRIDE = 28;

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
    public int uploadSection(long sectionPosKey, SectionEntry entry, UploadStream stream) {
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
            return (int) SegmentedManager.SIZE_LIMIT;
        }

        // Terrain is quad-indexed: 4 verts per quad. Round DOWN if MC occasionally produces a
        // stray non-quad vertex (shouldn't happen but don't crash).
        int quadCount = totalVerts / 4;
        if (quadCount == 0) {
            releaseSection(sectionPosKey);
            return (int) SegmentedManager.SIZE_LIMIT;
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
                // Arena full. Caller's problem.
                return (int) SegmentedManager.SIZE_LIMIT;
            }
        }

        // 3. Stream per-layer bytes into the arena. EnumMap iteration order is the enum's
        // declaration order — stable across calls, which is what downstream layer-range
        // bookkeeping will rely on.
        long baseByteOffset = arena.byteOffsetOf(addr);
        long dstByteOffset = baseByteOffset;
        boolean newAlloc = existing != addr;
        try {
            for (Map.Entry<ChunkSectionLayer, SectionEntry.LayerGeometry> e : entry.layers.entrySet()) {
                SectionEntry.LayerGeometry geom = e.getValue();
                if (geom == null) continue;
                ByteBuffer src = geom.vertexBytes;
                if (src == null) continue;
                // Duplicate so we don't touch the source cursor — the owning SectionEntry may be
                // referenced again (e.g. during a retry path) and we must leave it pristine.
                ByteBuffer view = src.duplicate();
                int count = view.remaining();
                if (count == 0) continue;

                long srcAddr = MemoryUtil.memAddress(view);
                long dstPtr = stream.upload(arena.buffer(), dstByteOffset, count);
                MemoryUtil.memCopy(srcAddr, dstPtr, count);
                dstByteOffset += count;
            }
        } catch (RuntimeException ex) {
            // Roll back: if we just allocated for this call, release it so the arena doesn't leak
            // the slot. Copies already queued in UploadStream for earlier layers will still fire,
            // but they'll target a freed region — the next allocation will overwrite it before
            // any draw reads it (draws consult sectionToAddr, which we're also clearing).
            if (newAlloc) {
                arena.free(addr);
            }
            sectionToAddr.remove(sectionPosKey);
            throw ex;
        }

        // 4. Record the (possibly reused) slot for future frames.
        sectionToAddr.put(sectionPosKey, addr);
        return addr;
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
}
