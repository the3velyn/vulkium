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
 *
 * <p><b>Ring buffer, not a single slot.</b> The CPU rewrites this buffer every frame and
 * the task shader reads it via BDA. With {@code MAX_SUBMITS_IN_FLIGHT=2} (Mojang's
 * {@code VulkanCommandEncoder}), a prior frame's task shader can still be reading the
 * buffer when the next frame's CPU-side {@code build()} is already overwriting it — a
 * WAR race with no synchronization. Symptom on NVIDIA 581.04 / RTX 3060 / Windows: 10-20
 * seconds of working rendering, then a sudden GPU hang that takes MC's
 * {@code VulkanCommandEncoder.submit} into its 5-second semaphore timeout. Not
 * reproducible on Linux (same hardware, different driver), so the driver's own serialization
 * happens to hide it there.
 *
 * <p>Fix: triple-buffer. Frame N writes to slot {@code N % 3} and plumbs that slot's
 * {@code deviceAddress()} into the scene UBO for the current frame. With three slots and
 * at most two submits in flight, the slot we write is guaranteed to not be in-flight on
 * the GPU. No CPU-side fence or timeline wait needed; the submission order + Mojang's
 * own submit-semaphore wait already force frame N+3's write to land after frame N's read
 * has retired.
 */
public final class OpaqueDispatchList implements AutoCloseable {
    /** Three slots: current frame + up to {@code MAX_SUBMITS_IN_FLIGHT=2} prior frames still
     *  reading. Four would be safer if Mojang ever bumps that cap, but we'd then need to
     *  mirror the bump. Three matches today's Mojang constant exactly. */
    private static final int RING_SLOTS = 3;

    private final StagingBuffer[] buffers = new StagingBuffer[RING_SLOTS];
    private final int capacity;
    private int lastCount;
    /** Highest prior-frame count per slot — positions from lastCount..maxEverWritten need
     *  sentinel re-stamping each frame so stale entries don't drive ghost dispatches.
     *  Tracked per-slot because each ring slot has its own independent write history. */
    private final int[] maxEverWrittenPerSlot = new int[RING_SLOTS];
    /** Which ring slot {@link #build} wrote into last. Callers index
     *  {@link #deviceAddress()} off this. */
    private int currentSlot = 0;
    /** Frame counter used to pick a ring slot. Incremented at the START of {@link #build}. */
    private long frameCounter = 0L;
    /** Reused sort scratch for front-to-back. Packed as (distance << 32) | (regionId << 8) |
     *  compactId so {@code Arrays.sort(long[])} produces an ascending-distance order and the
     *  low 32 bits are the emitted dispatch entry. Sized to capacity so no reallocation on
     *  hot path. */
    private long[] sortKeys;
    private boolean closed;

    public OpaqueDispatchList(int maxRegions) {
        int maxSections = maxRegions * RegionManager.SECTIONS_PER_REGION;
        this.capacity = maxSections;
        for (int s = 0; s < RING_SLOTS; s++) {
            StagingBuffer sb = StagingBuffer.allocateHostMappedBda((long) capacity * 4L);
            long base = sb.mappedPointer();
            for (int i = 0; i < capacity; i++) {
                MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
            }
            sb.flush(0L, (long) capacity * 4L);
            buffers[s] = sb;
        }
    }

    /** The device address of the CURRENT frame's ring slot — the one most recently written by
     *  {@link #build}. Callers plug this into the scene UBO's
     *  {@code opaqueDispatchListPtr}; the task shader reads from this frame's slot while
     *  the previous two frames can still safely be in flight on their own slots. */
    public long deviceAddress() { return buffers[currentSlot].deviceAddress(); }

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
        return build(sectionMgr, regionMgr, visibility, readbackPtr, 0L,
            false, 0, 0, 0);
    }

    /** Variant with front-to-back sort and section-level compaction.
     *
     * <p>{@code sectionReadbackPtr} (if non-zero): host-side per-section visibility from a
     *  prior frame's section_cull dispatch. When available, sections whose bit is 0 are
     *  skipped at list-build time — compounds with the region-level compaction so the GPU
     *  doesn't even launch a task workgroup for sections we already know are occluded.
     *
     * <p>{@code sortFrontToBack}: packs each entry by Manhattan distance and Arrays.sorts
     *  before emission so near sections dispatch first, improving early-Z rejection. */
    public int build(SectionManager sectionMgr, RegionManager regionMgr,
                     VisibilityTracker visibility,
                     long readbackPtr, long sectionReadbackPtr,
                     boolean sortFrontToBack,
                     int camSectionX, int camSectionY, int camSectionZ) {
        // Advance the ring slot FIRST so deviceAddress() returns the slot we're about to
        // write, not the one another frame may still be reading.
        currentSlot = (int) ((frameCounter++) % RING_SLOTS);
        final StagingBuffer buf = buffers[currentSlot];
        final long base = buf.mappedPointer();
        int n = 0;

        // Lazy-allocate the sort scratch. One-shot cost on first sorted frame; stable
        // allocation for the lifetime of this list.
        if (sortFrontToBack && sortKeys == null) {
            sortKeys = new long[capacity];
        }

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
            // When the section-level readback is armed, check each section's bit before
            // emitting. Byte-per-section memory read is ~1ns; overall loop stays sub-µs
            // per region.
            final long secReadbackBase = sectionReadbackPtr;  // local for hot-path branch elision
            if (sortFrontToBack) {
                // Pack (distance << 32) | (regionId << 8) | compactId so Arrays.sort yields
                // near-to-far ordering and the low 32 bits survive the cast back to int for
                // emission to the dispatch buffer.
                for (int compactId = 0; compactId < bound; compactId++) {
                    if (secReadbackBase != 0L) {
                        long idx = (long) rIdShifted + (long) compactId;
                        if ((MemoryUtil.memGetByte(secReadbackBase + idx) & 0x01) == 0) continue;
                    }
                    int dist = regionMgr.sectionDistance(regionId, compactId,
                        camSectionX, camSectionY, camSectionZ);
                    // Clamp negative / overflow into a positive 32-bit bucket so the shift
                    // below doesn't produce a negative long (which would sort before valid
                    // entries). Distance in section units fits easily in 16 bits at any RD.
                    if (dist < 0) dist = 0;
                    sortKeys[n++] =
                        ((long) dist << 32) | (long) (rIdShifted | compactId);
                    if (n >= cap) break;
                }
            } else {
                for (int compactId = 0; compactId < bound; compactId++) {
                    if (secReadbackBase != 0L) {
                        long idx = (long) rIdShifted + (long) compactId;
                        if ((MemoryUtil.memGetByte(secReadbackBase + idx) & 0x01) == 0) continue;
                    }
                    MemoryUtil.memPutInt(base + (long) n * 4L, rIdShifted | compactId);
                    n++;
                    if (n >= cap) break;
                }
            }
            if (n >= cap) break;
        }

        if (sortFrontToBack && n > 0) {
            java.util.Arrays.sort(sortKeys, 0, n);
            for (int i = 0; i < n; i++) {
                MemoryUtil.memPutInt(base + (long) i * 4L, (int) sortKeys[i]);
            }
        }

        // Sentinel-sweep trailing positions from n..maxEverWrittenForThisSlot so prior uses
        // of THIS slot (3 frames ago) don't leave ghost entries that would drive ghost
        // dispatches. Each ring slot tracks its own high-water mark independently.
        int prevMax = maxEverWrittenPerSlot[currentSlot];
        int sweepEnd = Math.max(prevMax, n);
        for (int i = n; i < sweepEnd; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        if (n > prevMax) maxEverWrittenPerSlot[currentSlot] = n;

        buf.flush(0L, (long) (sweepEnd + 1) * 4L);
        lastCount = n;
        return n;
    }

    @Override
    public void close() {
        if (closed) return;
        for (int s = 0; s < RING_SLOTS; s++) {
            if (buffers[s] != null) {
                try { buffers[s].close(); } catch (Throwable ignored) { /* swallow */ }
                buffers[s] = null;
            }
        }
        closed = true;
    }
}
