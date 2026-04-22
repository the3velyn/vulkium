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
    /** Ring-buffer of {@link StagingBuffer}s — three slots so the CPU can always write a
     *  slot that no in-flight GPU submit is reading. Matches the fix in
     *  {@link OpaqueDispatchList}; see that class's javadoc for the full rationale. Short
     *  version: {@code MAX_SUBMITS_IN_FLIGHT=2} in Mojang's {@code VulkanCommandEncoder},
     *  so with three ring slots the CPU-write never overlaps a GPU-read. Without this,
     *  NVIDIA 581.04 / Windows hangs immediately on world join with
     *  {@code translucencySortingLevel=QUADS}: the sorter rewrites this buffer every
     *  frame during ingest and races with the translucent task shader's BDA read. Linux
     *  hides the hazard via stricter internal serialization. */
    private static final int RING_SLOTS = 3;
    private final StagingBuffer[] buffers = new StagingBuffer[RING_SLOTS];
    private final int capacity;
    /** Which ring slot {@link #sort} most recently wrote (or left at its last contents
     *  when the sort-cache short-circuits). {@link #deviceAddress()} returns THIS slot. */
    private int currentSlot = 0;
    /** Frame counter — incremented each time {@link #sort} actually writes (cache miss).
     *  Drives slot rotation via {@code frameCounter % RING_SLOTS}. Cache hits do NOT
     *  advance — the cached data still lives in the previous slot and nothing rewrote
     *  it, so the GPU continues reading the correct contents. */
    private long frameCounter = 0L;

    /** Reusable CPU-side scratch to avoid per-frame allocation. */
    private int[] ids;
    private int[] dists;
    /** Packed (distance << 32) | gpuRef for Arrays.sort(long[]). Replaces the old O(n²)
     *  insertion sort — profiled at ~780µs/frame on 3060 at full RD and was the single
     *  biggest CPU hotspot until this change. */
    private long[] sortPairs;

    /** Per-slot highest {@code n} (entry count) written. Each slot tracks its own
     *  independent write history — when we cycle back to this slot three frames later,
     *  we only need to sweep sentinels over positions up to THIS slot's prior high-water
     *  mark, not the global max. */
    private final int[] maxEntriesEverWrittenPerSlot = new int[RING_SLOTS];

    /** Real (non-sentinel) entry count produced by the most recent {@link #sort}. Callers use
     *  this as the translucent mesh-task dispatch width — replacing the old brute-force
     *  maxRegionIndex*256 sweep with a tight count. */
    private int lastCount;
    public int count() { return lastCount; }

    /** Sort-cache key. When (cameraChunk, translucentVersion) is unchanged from the last sort
     *  call, the back-to-front order is identical — skip the entire scan + Arrays.sort +
     *  mapped-buffer rewrite, which is the single biggest remaining CPU line in PerfTracker
     *  (~160µs/frame on a full oceanfront). Initialized to a sentinel that can never match a
     *  real first call, so the first frame always takes the fast-path through the real sort. */
    private int cachedCx = Integer.MIN_VALUE;
    private int cachedCy = Integer.MIN_VALUE;
    private int cachedCz = Integer.MIN_VALUE;
    private int cachedVersion = Integer.MIN_VALUE;
    private boolean cacheValid = false;

    public TranslucentSectionSorter(int maxRegions) {
        int maxSections = maxRegions * RegionManager.SECTIONS_PER_REGION;
        this.capacity = maxSections;
        // 4 bytes per entry (uint32). At maxRegions=1024 → 1 MB backing buffer × 3 slots = 3 MB.
        for (int s = 0; s < RING_SLOTS; s++) {
            StagingBuffer sb = StagingBuffer.allocateHostMappedBda((long) capacity * 4L);
            long base = sb.mappedPointer();
            for (int i = 0; i < capacity; i++) {
                MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
            }
            sb.flush(0L, (long) capacity * 4L);
            buffers[s] = sb;
        }
        this.ids = new int[maxSections];
        this.dists = new int[maxSections];
        this.sortPairs = new long[maxSections];
    }

    /** Device address of the CURRENT ring slot — either the slot {@link #sort} last wrote
     *  (cache miss), or the slot whose cached result is still valid (cache hit). Plumbed
     *  into {@code sceneUniform.sortingRegionListPtr} every frame; the task shader indexes
     *  into this frame's slot via BDA. */
    public long deviceAddress() { return buffers[currentSlot].deviceAddress(); }

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
        // Sort-cache fast path. The sorted output is a pure function of (camera chunk pos,
        // translucentVersion) — if neither has changed since the last call, the last frame's
        // mapped-buffer contents are still valid. Bail out with no work and let `lastCount` /
        // deviceAddress continue to point at the already-correct result. This dominates in
        // stationary frames: the GPU reads the same list from the same BDA, the sort is a no-op,
        // and translucentSort drops to ~2µs/frame (just the equality checks).
        int version = sectionMgr.translucentVersion();
        if (cacheValid
                && cameraX == cachedCx
                && cameraY == cachedCy
                && cameraZ == cachedCz
                && version == cachedVersion) {
            // Cache hit — the current slot still holds the valid sorted list and no write
            // happened this frame, so no slot rotation either. deviceAddress() continues
            // to point at that slot; the GPU reads stable data without any CPU WAR race.
            return;
        }
        // Cache miss — advance to the next ring slot BEFORE writing, so we never overwrite
        // a slot that an in-flight GPU submit is still reading.
        currentSlot = (int) ((frameCounter++) % RING_SLOTS);
        final StagingBuffer buffer = buffers[currentSlot];
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

        // Write directly into the CURRENT SLOT's mapped pointer. Active entries at [0, n),
        // then sentinels from [n, maxEntriesEverWrittenPerSlot[currentSlot]] — we must
        // re-sentinel every position up to THIS slot's prior high-water mark, otherwise
        // stale refs from this slot's previous (larger-n) use linger and the task shader
        // dispatches phantom workgroups against them.
        long base = buffer.mappedPointer();
        for (int i = 0; i < n; i++) {
            // Low 32 bits of the packed sort key hold the gpuRef.
            MemoryUtil.memPutInt(base + (long) i * 4L, (int) sortPairs[i]);
        }
        int prevMax = maxEntriesEverWrittenPerSlot[currentSlot];
        int sentinelEnd = Math.max(n, prevMax);
        for (int i = n; i <= sentinelEnd; i++) {
            MemoryUtil.memPutInt(base + (long) i * 4L, 0xFFFFFFFF);
        }
        if (n > prevMax) maxEntriesEverWrittenPerSlot[currentSlot] = n;
        buffer.flush(0L, (long) (sentinelEnd + 1) * 4L);
        lastCount = n;

        // Cache the key for the next frame's fast-path check. Only reached when the fast-path
        // missed, so writing unconditionally here is correct.
        cachedCx = cameraX;
        cachedCy = cameraY;
        cachedCz = cameraZ;
        cachedVersion = version;
        cacheValid = true;
    }

    @Override
    public void close() {
        for (int s = 0; s < RING_SLOTS; s++) {
            if (buffers[s] != null) {
                try { buffers[s].close(); } catch (Throwable ignored) { /* swallow */ }
                buffers[s] = null;
            }
        }
    }
}
