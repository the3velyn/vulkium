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

    // Note: in-place shrink was attempted (commit 39f915e) to reclaim slot tails when
    // a rebuild produces fewer quads than the existing slot. It corrupted
    // SegmentedManager's coalesce invariants (free() assumes gaps between consecutive
    // TAKEN slots are spanned by one matching FREE block; shrink violated this). The
    // method here was removed. If revisited, the SegmentedManager.shrink path needs
    // to coalesce-on-emit with any adjacent free block AND the free() path needs to
    // tolerate non-matching gap fills.

    public DeviceBuffer buffer() { return buffer; }

    public int vertexFormatSize() { return vertexFormatSize; }

    public long byteOffsetOf(int quadAddr) {
        return Integer.toUnsignedLong(quadAddr) * 4L * vertexFormatSize;
    }

    public int byteSizeOf(int quadAddr) {
        return (int) segments.getSize(quadAddr) * 4 * vertexFormatSize;
    }

    /** Strict equality — slot must match the requested quad count exactly. Previous
     *  versions tried `>=` to let shrinks reuse, but that requires a working in-place
     *  shrink in {@link SegmentedManager}, which is non-trivial because the coalesce
     *  invariants in {@code free()} assume gaps between TAKEN slots are fully spanned
     *  by exactly-one matching FREE block — shrink violated that and produced
     *  IllegalStateException on the next free() of the shrunken slot. Strict-equality
     *  reuse is correct; rebuilds with different sizes fall through to alloc-then-
     *  free, which is the safe path. */
    public boolean canReuse(int addr, int quads) {
        return segments.getSize(addr) == quads;
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
