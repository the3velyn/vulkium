package me.cortex.vulkium.render;

import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionEntry;
import me.cortex.vulkium.vk.DeviceBuffer;
import me.cortex.vulkium.vk.UploadStream;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU-side per-section back-to-front sort for vulkium's translucent pass.
 *
 * <p>Each frame, walks the live section table and writes a list of section IDs into a
 * persistent-mapped buffer, ordered so the farthest sections come first. The translucent
 * task shader indexes this list instead of using {@code gl_WorkGroupID.x} directly, so
 * the mesh-shader dispatch emits its workgroups far-to-near — the blend order then matches
 * what MC's own uber-buffer path produces for vanilla translucent quads.
 *
 * <p>Per-quad sort within a section is NOT done here — that's MC's own sort happening at
 * chunk-build time, and we inherit it from the captured vertex order.
 *
 * <p>Entries are packed as 16-bit section IDs. Sentinel value {@code 0xFFFF} at the end of
 * the active range tells the task shader to emit zero mesh workgroups (out-of-bounds sentinel).
 */
public final class TranslucentSectionSorter implements AutoCloseable {
    /** Device-addressable backing buffer. Uploaded via UploadStream each frame. */
    private final DeviceBuffer buffer;
    private final int capacity;

    /** Reusable CPU-side scratch to avoid per-frame allocation: (sectionId, distanceSquared). */
    private int[] ids;
    private int[] dists;
    private final int maxSections;

    private boolean seeded = false;

    public TranslucentSectionSorter(int maxRegions) {
        this.maxSections = maxRegions * RegionManager.SECTIONS_PER_REGION;
        this.capacity = maxSections;
        this.buffer = DeviceBuffer.allocate((long) capacity * 2L);
        this.ids = new int[maxSections];
        this.dists = new int[maxSections];
    }

    public long deviceAddress() { return buffer.deviceAddress(); }

    /**
     * Populate the sort buffer from the live section table. Sections are ordered farthest-first.
     * Unused tail is filled with {@code 0xFFFF} sentinels. Only sections with non-zero translucent
     * quads contribute; opaque-only sections write the sentinel at their slot so the task shader
     * emits zero mesh workgroups for them in the translucent pass.
     *
     * @param live       the live section map (sectionPosKey → SectionEntry)
     * @param regionMgr  for sectionPosKey → regionRef lookup
     * @param cameraX    camera chunk X (block-space units divided by 16)
     * @param cameraY    camera chunk Y
     * @param cameraZ    camera chunk Z
     */
    public void sort(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SectionEntry> live,
                     RegionManager regionMgr,
                     me.cortex.vulkium.managers.SectionManager sectionMgr,
                     UploadStream stream,
                     int cameraX, int cameraY, int cameraZ) {
        int n = 0;
        // Walk live sections; record (regionRef, distSq). Skip sections with no translucent content
        // to keep the sort small.
        var it = live.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            SectionEntry entry = e.getValue();
            if (entry == null) continue;
            var trans = entry.layers.get(net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT);
            if (trans == null || trans.vertexCount == 0) continue;

            long key = e.getLongKey();
            int ref = sectionMgr.getRegionRef(key);
            if (ref < 0 || n >= capacity) continue;

            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            int dx = sx - cameraX, dy = sy - cameraY, dz = sz - cameraZ;
            int d = dx * dx + dy * dy + dz * dz;

            ids[n] = ref;
            dists[n] = d;
            n++;
        }

        // Insertion sort descending by distance (far first). For typical live-section counts
        // (a few hundred translucent sections max within RD) this is fine; upgrade to radix
        // / timsort if profiling shows the sort dominates.
        for (int i = 1; i < n; i++) {
            int kId = ids[i], kD = dists[i];
            int j = i - 1;
            while (j >= 0 && dists[j] < kD) {
                ids[j + 1] = ids[j];
                dists[j + 1] = dists[j];
                j--;
            }
            ids[j + 1] = kId;
            dists[j + 1] = kD;
        }

        // First frame: seed the whole buffer with 0xFFFF sentinels so stale memory past `n`
        // doesn't masquerade as valid section IDs when the shader reads those slots.
        // Subsequent frames: just write n + 1 shorts (actual entries + trailing sentinel) to
        // minimize staging-ring churn.
        if (!seeded) {
            long seedBase = stream.upload(buffer, 0L, capacity * 2);
            for (int i = 0; i < capacity; i++) {
                MemoryUtil.memPutShort(seedBase + (long) i * 2L, (short) 0xFFFF);
            }
            seeded = true;
        }

        int byteCount = (n + 1) * 2;
        long base = stream.upload(buffer, 0L, byteCount);
        for (int i = 0; i < n; i++) {
            MemoryUtil.memPutShort(base + (long) i * 2L, (short) (ids[i] & 0xFFFF));
        }
        MemoryUtil.memPutShort(base + (long) n * 2L, (short) 0xFFFF);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
