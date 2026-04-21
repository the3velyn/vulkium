package me.cortex.vulkium.render;

import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionManager;
import me.cortex.vulkium.vk.StagingBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Compact opaque-section dispatch list — the perf twin of {@link TranslucentSectionSorter}
 * but for the opaque pass.
 *
 * <p>Previously vulkium dispatched {@code maxRegionIndex × 256} task workgroups per frame
 * (~45k at 174 allocated regions) and relied on the task shader to no-op empty slots. With
 * only ~3.5k actually-populated sections that's a lot of wasted task-shader launches.
 *
 * <p>This class builds a compact list of GPU-compact section IDs
 * {@code (regionId << 8) | compactIdInRegion} into a host-mapped BDA buffer each frame,
 * filtering by visible regions via {@link VisibilityTracker}. The task shader reads
 * {@code list[gl_WorkGroupID.x]} and redirects, so the dispatch count drops to exactly
 * the number of live-and-visible sections.
 *
 * <p>No sort — opaque depth-test handles ordering. Structurally mirrors
 * {@code TranslucentSectionSorter} (host-mapped BDA, uint32 entries, sentinel-swept
 * watermark) so the shader redirect pattern stays consistent across both passes.
 */
public final class OpaqueDispatchList implements AutoCloseable {
    private final StagingBuffer buffer;
    private final int capacity;
    private int lastCount;
    /** Highest prior-frame count — positions from lastCount..maxEverWritten need sentinel
     *  re-stamping each frame so stale entries don't drive ghost dispatches. */
    private int maxEverWritten;
    private boolean closed;

    public OpaqueDispatchList(int maxRegions) {
        int maxSections = maxRegions * RegionManager.SECTIONS_PER_REGION;
        this.capacity = maxSections;
        this.buffer = StagingBuffer.allocateHostMappedBda((long) capacity * 4L);
        long base = buffer.mappedPointer();
        for (int i = 0; i < capacity; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        buffer.flush(0L, (long) capacity * 4L);
    }

    public long deviceAddress() { return buffer.deviceAddress(); }

    /** Count of real (non-sentinel) entries produced by the most recent {@link #build}. */
    public int count() { return lastCount; }

    /**
     * Rebuild the list for this frame. Iterates vulkium's live section table, filters by
     * visible regions, skips sections that can't resolve their compact ID (stale during
     * region teardown). Returns the number of entries written (= {@link #count()} after).
     */
    public int build(SectionManager sectionMgr, RegionManager regionMgr,
                     VisibilityTracker visibility) {
        final long base = buffer.mappedPointer();
        final int[] nBox = { 0 };

        // Iterate by VISIBLE REGION × per-region compact section IDs. This is O(visibleRegions
        // × sectionsPerRegion) instead of O(liveSections). At typical RDs it's a 10-20×
        // reduction in CPU work because live-section counts sit in the thousands while
        // visible-region × packed-sections lands in the low hundreds.
        visibility.forEachVisibleRegion(regionId -> {
            int count = regionMgr.regionSectionCount(regionId);
            if (count == 0 || nBox[0] >= capacity) return;
            final int rIdShifted = regionId << 8;
            // id2pos is already dense over [0, count), so compact IDs are just 0..count-1.
            for (int compactId = 0; compactId < count && nBox[0] < capacity; compactId++) {
                int gpuRef = rIdShifted | compactId;
                MemoryUtil.memPutInt(base + (long) nBox[0] * 4L, gpuRef);
                nBox[0]++;
            }
        });
        int n = nBox[0];

        // Sentinel-sweep trailing positions from n..maxEverWritten so prior frames' higher
        // counts don't leave ghost entries for the GPU to dispatch.
        int sweepEnd = Math.max(maxEverWritten, n);
        for (int i = n; i < sweepEnd; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        if (n > maxEverWritten) maxEverWritten = n;

        buffer.flush(0L, (long) (sweepEnd + 1) * 4L);
        lastCount = n;
        return n;
    }

    @Override
    public void close() {
        if (closed) return;
        buffer.close();
        closed = true;
    }
}
