package me.cortex.vulkium.render;

import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionEntry;
import me.cortex.vulkium.vk.StagingBuffer;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU-side back-to-front section sort for vulkium's translucent pass.
 *
 * <p>Each frame, walks the live section table and writes a list of GPU-compact section IDs
 * into a persistent-mapped buffer, ordered so the farthest sections come first. The translucent
 * task shader reads {@code sortList[gl_WorkGroupID.x]} to redirect its dispatch, so the
 * mesh-shader workgroups run far-to-near — the blend order then matches vanilla's translucent
 * render across the whole frame.
 *
 * <p>Within-section per-quad sort is already handled at upload time by applying MC's sorted
 * translucent index buffer as a vertex permutation (see {@link TerrainUploader#
 * uploadSectionSplit}). This class handles ONLY the cross-section order.
 *
 * <p><b>Buffer choice:</b> a host-mapped BDA staging buffer (allocated via
 * {@link StagingBuffer#allocateHostMappedBda(long)}). Writes go directly to the mapped pointer
 * each frame — no UploadStream staging-ring churn, so the upload ring stays dedicated to
 * chunk-mesh uploads. The shader reads the list through a buffer-reference pointer in the
 * scene UBO ({@code sortingRegionListPtr}).
 *
 * <p>Encoding: uint32 per entry. The value is a GPU-compact section index
 * {@code (regionId << 8) | compactIdInRegion} — exactly the layout the dispatch's
 * {@code gl_WorkGroupID.x} maps to when reading {@code sectionData.data[sectionId]}. With
 * {@code maxRegions=1024} a section ID can reach {@code (1023<<8)|255 = 262143}, so a 16-bit
 * entry would silently truncate mid-ocean and drop those sections from the translucent pass
 * (the symptom: blocks of water rendering "opaque" where the seafloor shows through with no
 * blend). Sentinel value {@code 0xFFFFFFFF} signals "no section — emit zero mesh workgroups".
 */
public final class TranslucentSectionSorter implements AutoCloseable {
    /** Host-mapped BDA staging. Writes never go through UploadStream. */
    private final StagingBuffer buffer;
    private final int capacity;

    /** Reusable CPU-side scratch to avoid per-frame allocation. */
    private int[] ids;
    private int[] dists;
    /** Packed (distance << 32) | gpuRef for Arrays.sort(long[]). Replaces the old O(n²)
     *  insertion sort — profiled at ~780µs/frame on 3060 at full RD and was the single
     *  biggest CPU hotspot until this change. */
    private long[] sortPairs;

    /** Highest {@code n} (entry count) written in any prior frame. Each subsequent frame must
     *  re-sentinel positions past its own (possibly smaller) {@code n} up through this
     *  watermark, otherwise stale entries from the high-water frame linger on the GPU side
     *  and the task shader redirects to ghost section refs — visible as phantom translucent
     *  quads that "self-correct" only after the dispatch width shrinks below their index. */
    private int maxEntriesEverWritten;

    /** Real (non-sentinel) entry count produced by the most recent {@link #sort}. Callers use
     *  this as the translucent mesh-task dispatch width — replacing the old brute-force
     *  maxRegionIndex*256 sweep with a tight count. */
    private int lastCount;
    public int count() { return lastCount; }

    public TranslucentSectionSorter(int maxRegions) {
        int maxSections = maxRegions * RegionManager.SECTIONS_PER_REGION;
        this.capacity = maxSections;
        // 4 bytes per entry (uint32). At maxRegions=1024 → 1 MB backing buffer.
        this.buffer = StagingBuffer.allocateHostMappedBda((long) capacity * 4L);
        this.ids = new int[maxSections];
        this.dists = new int[maxSections];
        this.sortPairs = new long[maxSections];
        // Seed once with sentinels; subsequent frames overwrite only the active prefix +
        // trailing sentinel, so anything past `n` remains 0xFFFFFFFF.
        long base = buffer.mappedPointer();
        for (int i = 0; i < capacity; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        buffer.flush(0L, (long) capacity * 4L);
    }

    public long deviceAddress() { return buffer.deviceAddress(); }

    /**
     * Populate the sort list from the live section table. Sections are ordered farthest-first.
     * Only sections with non-zero translucent content contribute.
     *
     * @param live       the live section map (sectionPosKey → SectionEntry)
     * @param regionMgr  for pos→compact-id translation
     * @param cameraX    camera chunk X
     * @param cameraY    camera chunk Y
     * @param cameraZ    camera chunk Z
     */
    public void sort(it.unimi.dsi.fastutil.longs.LongOpenHashSet translucentKeys,
                     RegionManager regionMgr,
                     me.cortex.vulkium.managers.SectionManager sectionMgr,
                     int cameraX, int cameraY, int cameraZ) {
        int n = 0;
        var it = translucentKeys.longIterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int ref = sectionMgr.getRegionRef(key);
            if (ref < 0 || n >= capacity) continue;

            // Convert pos-based ref → GPU-compact ref. setSectionData writes data to the
            // compact slot, and the shader dispatches by compact slot, so the sort list must
            // also reference compact slots.
            int compactId;
            try {
                compactId = regionMgr.getSectionRefId(ref);
            } catch (RuntimeException ex) {
                // Race: region got torn down between the live-table read and here. Skip.
                continue;
            }
            int gpuRef = (ref & ~0xFF) | (compactId & 0xFF);

            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            int dx = sx - cameraX, dy = sy - cameraY, dz = sz - cameraZ;
            int d = dx * dx + dy * dy + dz * dz;

            // Pack (priority, gpuRef) into one long so Arrays.sort does the whole thing
            // in O(n log n). Negating the distance converts Arrays.sort's ascending order
            // into descending-by-distance (farthest first), which is what the translucent
            // blend requires. High 32 bits: -d (cast so Java's int sign-extension lands
            // correctly). Low 32 bits: gpuRef as unsigned.
            sortPairs[n] = (((long) -d) << 32) | (gpuRef & 0xFFFFFFFFL);
            n++;
        }

        // Arrays.sort(long[]) is a dual-pivot quicksort on long, hit by escape analysis +
        // JIT-inline; replaces the old insertion sort that was ~2500µs/frame on heavy
        // translucent scenes (oceans). Sorts the first n entries ascending by packed key.
        java.util.Arrays.sort(sortPairs, 0, n);

        // Write directly into the mapped pointer. Active entries at [0, n), then sentinels
        // from [n, maxEntriesEverWritten] — we must re-sentinel every slot up to the prior
        // high-water mark, otherwise stale refs from a previous (larger-n) frame still sit in
        // the buffer and the task shader dispatches phantom workgroups against them.
        long base = buffer.mappedPointer();
        for (int i = 0; i < n; i++) {
            // Low 32 bits of the packed sort key hold the gpuRef.
            MemoryUtil.memPutInt(base + (long) i * 4L, (int) sortPairs[i]);
        }
        // Inclusive-end sentinel sweep from n through the high-water mark. Bounded by capacity.
        int sentinelEnd = Math.max(n, maxEntriesEverWritten);
        for (int i = n; i <= sentinelEnd; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        if (n > maxEntriesEverWritten) maxEntriesEverWritten = n;
        buffer.flush(0L, (long) (sentinelEnd + 1) * 4L);
        lastCount = n;
    }

    @Override
    public void close() {
        buffer.close();
    }
}
