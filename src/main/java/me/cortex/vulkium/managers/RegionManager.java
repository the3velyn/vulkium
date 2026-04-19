package me.cortex.vulkium.managers;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.vulkium.managers.util.IdProvider;
import me.cortex.vulkium.vk.DeviceBuffer;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Port of nvidium's {@code RegionManager}. Regions are 8×4×8 section groups; each region holds
 * up to 256 sections (8*4*8) with a dense pos↔id bijection so dispatches can address sections
 * by {@code (regionId << 8) | posInRegion}. Region metadata (AABB + transform + section table)
 * lives in two GPU buffers: {@code regionBuffer} ({@link #META_SIZE} bytes per region) and
 * {@code sectionBuffer} ({@link SectionCompileSlot#SECTION_SIZE} bytes per section).
 *
 * <p>Dirty regions accumulate until {@link #drainDirty(DirtyRegionVisitor)} is called from the
 * render thread; the visitor receives each region's CPU-side section-data pointer so it can
 * drive a staging upload. Upload wiring lands in V7; this manager is V4's CPU-side ledger.
 */
public final class RegionManager implements AutoCloseable {
    public static final int MAX_TRANSFORMATION_SIZE_BITS = 10;
    public static final int MAX_TRANSFORMATION_COUNT = 1 << MAX_TRANSFORMATION_SIZE_BITS;

    /** Bytes per region in {@code regionBuffer}. Two 64-bit words: AABB+count+x|y, z+transformId. */
    public static final int META_SIZE = 16;

    /** Bytes per section entry in {@code sectionBuffer}. Matches nvidium's SECTION_SIZE. */
    public static final int SECTION_META_SIZE = 32;

    /** Per-region section-meta slab in {@code sectionBuffer}. 256 sections × SECTION_META_SIZE bytes. */
    public static final int SECTIONS_PER_REGION = 256;
    public static final int TOTAL_SECTION_META_SIZE = SECTIONS_PER_REGION * SECTION_META_SIZE;

    private static final boolean SAFETY_CHECKS = Boolean.getBoolean("vulkium.safetyChecks");

    private final DeviceBuffer regionBuffer;
    private final DeviceBuffer sectionBuffer;

    private final Long2IntOpenHashMap regionTransformationIdMapping = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap regionMap = new Long2IntOpenHashMap();
    private final IdProvider idProvider = new IdProvider();
    private final Region[] regions;

    private final ArrayDeque<Region> dirtyRegions = new ArrayDeque<>();

    private boolean closed;

    public RegionManager(int maxRegions) {
        this.regionMap.defaultReturnValue(-1);
        this.regionBuffer = DeviceBuffer.allocate((long) maxRegions * META_SIZE);
        this.sectionBuffer = DeviceBuffer.allocate((long) maxRegions * TOTAL_SECTION_META_SIZE);
        this.regions = new Region[maxRegions];
    }

    public interface DirtyRegionVisitor {
        /**
         * @param regionId  region's GPU slot index.
         * @param removed   true if region was destroyed (GPU slab should be zeroed); false for
         *                  a normal update.
         * @param metaSrcAddr  native pointer to a 16-byte region-meta buffer, valid for the call.
         * @param sectionSrcAddr  native pointer to 256*SECTION_SIZE section slab, valid for the call.
         */
        void visit(int regionId, boolean removed, long metaSrcAddr, long sectionSrcAddr);
    }

    /** Render-thread consumer of dirty regions. Clears the dirty list. */
    public void drainDirty(DirtyRegionVisitor visitor) {
        if (dirtyRegions.isEmpty()) return;
        // Scratch 16-byte meta buffer filled on the render thread per region.
        long metaScratch = MemoryUtil.nmemAlloc(META_SIZE);
        try {
            while (!dirtyRegions.isEmpty()) {
                Region region = dirtyRegions.pop();
                region.isDirty = false;
                if (region.isRemoved) {
                    if (regions[region.id] == null) {
                        MemoryUtil.memSet(metaScratch, -1, META_SIZE);
                        visitor.visit(region.id, true, metaScratch, 0L);
                    }
                } else {
                    writeRegionMeta(metaScratch, region);
                    visitor.visit(region.id, false, metaScratch, region.sectionData);
                }
            }
        } finally {
            MemoryUtil.nmemFree(metaScratch);
        }
    }

    private void writeRegionMeta(long upload, Region region) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int lastIdx = 0;
        for (int i = 0; i < SECTIONS_PER_REGION; i++) {
            if (region.pos2id[i] == -1) continue;
            int x = i & 7;
            int y = i >>> 6;
            int z = (i >>> 3) & 7;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
            lastIdx = i;
        }

        long size = (long) (maxY - minY) << 62 | (long) (maxX - minX) << 59 | (long) (maxZ - minZ) << 56;
        long count = (long) lastIdx << 48;
        long x = (((long) region.rx << 3) + minX & ((1 << 24) - 1)) << 24;
        long y = (((long) region.ry << 2) + minY & ((1 << 24) - 1));
        long z = (((long) region.rz << 3) + minZ & ((1 << 24) - 1)) << (64 - 24);
        long transformationId = (long) region.transformationId << (64 - 24 - MAX_TRANSFORMATION_SIZE_BITS);
        MemoryUtil.memPutLong(upload, size | count | x | y);
        MemoryUtil.memPutLong(upload + 8, z | transformationId);
    }

    public int getSectionRefId(int section) {
        Region region = regions[section >>> 8];
        int id = region.pos2id[section & 0xFF];
        if (id < 0 || id >= SECTIONS_PER_REGION) {
            throw new IllegalStateException();
        }
        return id;
    }

    /** Returns a pointer to where the section data can be read or updated. Marks region dirty. */
    public long setSectionData(int sectionId) {
        Region region = regions[sectionId >>> 8];
        sectionId &= 0xFF;
        sectionId = region.pos2id[sectionId];
        if (sectionId < 0 || sectionId >= SECTIONS_PER_REGION) {
            throw new IllegalStateException();
        }
        markDirty(region);
        return region.sectionData + (long) sectionId * SECTION_META_SIZE;
    }

    public void removeSection(int sectionId) {
        Region region = regions[sectionId >>> 8];
        sectionId &= 0xFF;
        if (region == null) {
            throw new IllegalStateException("Region is null");
        }
        int sectionPos = sectionId;
        sectionId = region.pos2id[sectionId];

        MemoryUtil.memSet(region.sectionData + (long) sectionId * SECTION_META_SIZE,
            0, SECTION_META_SIZE);
        region.pos2id[sectionPos] = -1;
        region.id2pos[sectionId] = -1;
        region.verifyIntegrity();

        int endId = --region.count;
        if (endId != sectionId) {
            int oldPos = region.id2pos[endId];
            if (oldPos == -1) throw new IllegalStateException();

            MemoryUtil.memCopy(
                region.sectionData + (long) endId * SECTION_META_SIZE,
                region.sectionData + (long) sectionId * SECTION_META_SIZE,
                SECTION_META_SIZE);
            MemoryUtil.memSet(
                region.sectionData + (long) endId * SECTION_META_SIZE,
                0, SECTION_META_SIZE);

            if (region.id2pos[endId] == -1 || region.pos2id[oldPos] == -1) {
                throw new IllegalStateException();
            }

            region.id2pos[endId] = -1;
            region.pos2id[oldPos] = -1;
            region.id2pos[sectionId] = oldPos;
            region.pos2id[oldPos] = sectionId;

            long ptr = region.sectionData + (long) sectionId * SECTION_META_SIZE + 4;
            int data = MemoryUtil.memGetInt(ptr);
            data &= ~(0xFF << 18);
            data |= sectionId << 18;
            MemoryUtil.memPutInt(ptr, data);

            region.verifyIntegrity();
        }

        if (region.count == 0) {
            region.isRemoved = true;
            region.free();
            regions[region.id] = null;
            idProvider.release(region.id);
            regionMap.remove(region.key);
        }

        markDirty(region);
        region.verifyIntegrity();
    }

    public int allocateSection(int sectionX, int sectionY, int sectionZ) {
        long regionKey = SectionPos.asLong(sectionX >> 3, sectionY >> 2, sectionZ >> 3);
        int regionId = regionMap.computeIfAbsent(regionKey, k -> idProvider.provide());

        if (regions[regionId] == null) {
            regions[regionId] = new Region(regionId, sectionX >> 3, sectionY >> 2, sectionZ >> 3);
            regions[regionId].transformationId = regionTransformationIdMapping.get(regionKey);
        }
        Region region = regions[regionId];

        int sectionKey = ((sectionY & 3) << 6) | (sectionX & 7) | ((sectionZ & 7) << 3);
        int sectionId = region.count++;
        if (region.pos2id[sectionKey] != -1 || region.id2pos[sectionId] != -1) {
            throw new IllegalStateException("Section id not free!");
        }

        region.pos2id[sectionKey] = sectionId;
        region.id2pos[sectionId] = sectionKey;

        markDirty(region);

        region.verifyIntegrity();
        return sectionKey | (regionId << 8);
    }

    private void markDirty(Region region) {
        if (region.isDirty) return;
        region.isDirty = true;
        dirtyRegions.add(region);
    }

    public int regionCount() { return regionMap.size(); }
    public int maxRegions() { return regions.length; }
    public int maxRegionIndex() { return idProvider.maxIndex(); }

    public boolean regionExists(int regionId) {
        return regions[regionId] != null;
    }

    public int distance(int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        Region region = regions[regionId];
        return (Math.abs((region.rx << 3) + 4 - camChunkX)
            + Math.abs((region.ry << 2) + 2 - camChunkY)
            + Math.abs((region.rz << 3) + 4 - camChunkZ)
            + Math.abs((region.rx << 3) + 3 - camChunkX)
            + Math.abs((region.ry << 2) + 1 - camChunkY)
            + Math.abs((region.rz << 3) + 3 - camChunkZ)) >> 1;
    }

    public DeviceBuffer regionBuffer() { return regionBuffer; }
    public DeviceBuffer sectionBuffer() { return sectionBuffer; }

    public long regionBufferAddress() { return regionBuffer.deviceAddress(); }
    public long sectionBufferAddress() { return sectionBuffer.deviceAddress(); }

    public long regionIdToKey(int regionId) {
        if (regions[regionId] == null) throw new IllegalStateException();
        return regions[regionId].key;
    }

    public void setRegionTransformId(int x, int y, int z, int id) {
        if (id < 0 || id >= MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Transformation id out of bounds");
        }
        long regionKey = SectionPos.asLong(x, y, z);
        int oldId = regionTransformationIdMapping.put(regionKey, id);
        if (oldId != id) {
            int regionId = regionMap.get(regionKey);
            if (regionId == -1) return;
            Region region = regions[regionId];
            region.transformationId = id;
            markDirty(region);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Region r : regions) {
            if (r != null) r.free();
        }
        Arrays.fill(regions, null);
        regionBuffer.close();
        sectionBuffer.close();
    }

    private static final class Region {
        private final int rx, ry, rz;
        private final long key;
        private final int id;

        int transformationId = 0;
        int count;
        final int[] pos2id = new int[SECTIONS_PER_REGION];
        final int[] id2pos = new int[SECTIONS_PER_REGION];

        boolean isDirty;
        boolean isRemoved;

        /** Per-region native slab of section meta, uploaded in one shot when dirty. */
        final long sectionData = MemoryUtil.nmemAlloc((long) TOTAL_SECTION_META_SIZE);

        Region(int id, int rx, int ry, int rz) {
            Arrays.fill(pos2id, -1);
            Arrays.fill(id2pos, -1);
            MemoryUtil.memSet(sectionData, 0, TOTAL_SECTION_META_SIZE);
            this.key = SectionPos.asLong(rx, ry, rz);
            this.id = id;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }

        void free() {
            MemoryUtil.nmemFree(sectionData);
        }

        void verifyIntegrity() {
            if (!SAFETY_CHECKS) return;
            for (int i = 0; i < SECTIONS_PER_REGION; i++) {
                if (id2pos[i] != -1 && pos2id[id2pos[i]] != i) throw new IllegalStateException();
                if (pos2id[i] != -1 && id2pos[pos2id[i]] != i) throw new IllegalStateException();
            }
        }
    }
}
