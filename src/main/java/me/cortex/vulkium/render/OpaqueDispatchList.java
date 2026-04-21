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
     *
     * <p>If {@code readbackPtr != 0L}, the CPU-side regionVisibility readback (populated by
     * a prior frame's region_cull + vkCmdCopyBuffer, see
     * {@link Renderer#regionVisibilityReadbackPtr}) is consulted to skip occluded regions
     * at list-build time. Savings compound with the GPU-side task-shader gate: excluded
     * regions don't even dispatch workgroups here, saving both task-shader launches and the
     * mesh/frag work those tasks would have emitted for visible sections in those regions.
     * Frame-lagged by the GPU queue depth so the bits reflect what was occluded ~1-2 frames
     * ago — conservative under motion at 500+ FPS.
     */
    public int build(SectionManager sectionMgr, RegionManager regionMgr,
                     VisibilityTracker visibility, long readbackPtr) {
        final long base = buffer.mappedPointer();
        int n = 0;

        // Iterate by VISIBLE REGION × per-region compact section IDs via direct array
        // access — no lambda / capture allocation on the per-frame hot path. At typical
        // RDs, visibleRegions × packedSections runs in the low hundreds, vs. the multi-
        // thousand live-section walk the old path did.
        int[] visIds = visibility.visibleRegionsArray();
        int visCount = visibility.visibleRegionCount();
        final int cap = capacity;
        for (int k = 0; k < visCount; k++) {
            int regionId = visIds[k];
            // CPU-side HZB compaction: when the readback pointer is valid (cull has run at
            // least once), skip regions that were marked occluded. Falls back to "include
            // all" when readbackPtr == 0, which keeps correctness for the cull-off and
            // first-frame-after-enable paths where the bits can't yet be trusted.
            if (readbackPtr != 0L
                && (MemoryUtil.memGetByte(readbackPtr + regionId) & 0x01) == 0) {
                continue;
            }
            int sectCount = regionMgr.regionSectionCount(regionId);
            if (sectCount == 0) continue;
            int rIdShifted = regionId << 8;
            int bound = Math.min(sectCount, cap - n);
            // id2pos is dense over [0, count), so compact IDs are just 0..count-1.
            for (int compactId = 0; compactId < bound; compactId++) {
                MemoryUtil.memPutInt(base + (long) (n + compactId) * 4L, rIdShifted | compactId);
            }
            n += bound;
            if (n >= cap) break;
        }

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
