package me.cortex.vulkium.managers;

import me.cortex.vulkium.managers.util.SegmentedManager;
import me.cortex.vulkium.vk.DeviceBuffer;

/**
 * Quad-granular GPU buffer allocator. Ported from nvidium's {@code BufferArena} — the
 * algorithm is identical; the backing buffer is VK-backed instead of GL-backed, and sparse-
 * bound variants are deferred (V5 sparse task).
 *
 * <p>Allocates quads (4 vertices) in chunks; addresses are quad indices that the mesh shader
 * decodes via {@code (id << 2) + lane}. Address 0 is reserved as a sentinel (allocated at
 * construction).
 */
public final class BufferArena implements AutoCloseable {
    private final SegmentedManager segments = new SegmentedManager();
    private final DeviceBuffer buffer;
    private final int vertexFormatSize;
    private final long memorySize;
    private long totalQuads;
    private boolean closed;

    public BufferArena(long memory, int vertexFormatSize) {
        this.vertexFormatSize = vertexFormatSize;
        this.memorySize = memory;
        // Non-sparse path for now. SparseDeviceBuffer variant lands when V5 sparse ships.
        this.buffer = DeviceBuffer.allocate(memory);
        this.segments.setLimit(memory / (4L * vertexFormatSize));
        // Reserve index 0 as a sentinel.
        this.allocQuads(1);
    }

    /** Returns a quad-granular address, or {@link SegmentedManager#SIZE_LIMIT} if full. */
    public int allocQuads(int quadCount) {
        int addr = (int) segments.alloc(quadCount);
        if (addr != (int) SegmentedManager.SIZE_LIMIT) {
            totalQuads += quadCount;
        }
        // Sparse page-commit hook will go here.
        return addr;
    }

    public void free(int addr) {
        int count = segments.free(addr);
        totalQuads -= count;
        // Sparse page-release hook will go here.
    }

    /** Shrink an existing slot to {@code newQuads}, returning the tail to the free pool.
     *  No-op if the slot is already that size or smaller. Called by TerrainUploader when
     *  a rebuild's new quad count is smaller than the existing slot's capacity — without
     *  this, the shrink-reuse path leaves wasted bytes inside slots that progressively
     *  oversize themselves over a long session of mixed block edits. */
    public void shrink(int addr, int newQuads) {
        if (newQuads <= 0) return;
        long oldQuads = segments.getSize(addr);
        if (newQuads >= oldQuads) return;
        int released = segments.shrink(addr, newQuads);
        totalQuads -= released;
    }

    public DeviceBuffer buffer() { return buffer; }

    public int vertexFormatSize() { return vertexFormatSize; }

    public long byteOffsetOf(int quadAddr) {
        return Integer.toUnsignedLong(quadAddr) * 4L * vertexFormatSize;
    }

    public int byteSizeOf(int quadAddr) {
        return (int) segments.getSize(quadAddr) * 4 * vertexFormatSize;
    }

    /** A section's existing slot can host any quad count up to its allocated size — the
     *  extra space is wasted until reallocation but the geometry is valid for the lower
     *  count. Letting rebuilds shrink-reuse means a block edit that removes geometry
     *  (e.g. mined a wall, broke last of a tree) doesn't have to alloc-new and can't
     *  hit SIZE_LIMIT just because the arena's full of unrelated chunks. The exact-
     *  match path was a perf optimisation (no waste) but failed correctness when arena
     *  pressure rejected legitimate rebuilds. */
    public boolean canReuse(int addr, int quads) {
        return segments.getSize(addr) >= quads;
    }

    public long totalQuads() { return totalQuads; }
    public long memorySize() { return memorySize; }

    public int allocatedMB() { return (int) (memorySize / (1024 * 1024)); }
    public int usedMB() { return (int) ((totalQuads * vertexFormatSize * 4) / (1024 * 1024)); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        buffer.close();
    }
}
