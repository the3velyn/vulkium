package me.cortex.vulkium.managers;

import com.mojang.blaze3d.vertex.MeshData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns vulkium's view of per-section geometry: the in-memory section table, upload queue, and
 * (future) GPU residency.
 *
 * <p>At this milestone {@code SectionManager} acts as a producer/consumer hand-off between MC's
 * worker threads (which produce {@link SectionEntry} via {@link SectionCapture}) and the render
 * thread (which drains the queue per frame). No GPU upload yet — that lands with V7. For now
 * drained entries are just counted + their buffers freed so we can measure throughput.
 *
 * <p>Thread model:
 * <ul>
 *   <li>Worker thread: {@link #offerFromCompile} copies MC's raw bytes into fresh direct
 *       buffers owned by this subsystem, and enqueues a {@link SectionEntry}.</li>
 *   <li>Render thread: {@link #drainPending} pops entries and processes them.</li>
 * </ul>
 */
public final class SectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/section");
    private static final SectionManager INSTANCE = new SectionManager();

    /** sectionPos (long from SectionPos.asLong) → live section. */
    private final Long2ObjectOpenHashMap<SectionEntry> live = new Long2ObjectOpenHashMap<>();

    /** sectionPos (long) → RegionManager's packed (regionId << 8) | posInRegion id. */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap sectionToRegionRef =
        new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    /** Worker → render hand-off. Unbounded; trimmed per frame by drainPending(). */
    private final ConcurrentLinkedQueue<PendingIngest> ingestQueue = new ConcurrentLinkedQueue<>();

    /** Lazy-initialized on the render thread the first time we drain. */
    private RegionManager regionManager;

    private long drained = 0L;
    private long droppedBytes = 0L;

    private SectionManager() {
        sectionToRegionRef.defaultReturnValue(-1);
    }

    public static SectionManager get() { return INSTANCE; }

    /** Called from the render thread during init. Binds the region ledger. */
    public void bindRegionManager(RegionManager manager) {
        this.regionManager = manager;
    }

    public RegionManager regionManager() { return regionManager; }

    /**
     * Called from the worker thread that compiled a section. Copies MC's vertex/index bytes
     * into fresh direct buffers (MC would otherwise recycle its {@code ByteBufferBuilder.Result}
     * memory before we get to use it).
     */
    public void offerFromCompile(long sectionPosKey, SectionCompiler.Results results) {
        Map<ChunkSectionLayer, MeshData> layers = results.renderedLayers;
        if (layers.isEmpty()) return;

        SectionEntry entry = new SectionEntry();
        for (Map.Entry<ChunkSectionLayer, MeshData> e : layers.entrySet()) {
            MeshData m = e.getValue();
            if (m == null) continue;

            ByteBuffer srcVb = m.vertexBuffer();
            ByteBuffer srcIb = m.indexBuffer();
            ByteBuffer vb = null;
            ByteBuffer ib = null;
            if (srcVb != null && srcVb.remaining() > 0) {
                vb = MemoryUtil.memAlloc(srcVb.remaining());
                vb.put(srcVb.duplicate()).flip();
            }
            if (srcIb != null && srcIb.remaining() > 0) {
                ib = MemoryUtil.memAlloc(srcIb.remaining());
                ib.put(srcIb.duplicate()).flip();
            }
            MeshData.DrawState ds = m.drawState();
            entry.layers.put(e.getKey(), new SectionEntry.LayerGeometry(
                vb, ib,
                ds == null ? 0 : ds.vertexCount(),
                ds == null ? 0 : ds.indexCount(),
                ds));
        }

        ingestQueue.offer(new PendingIngest(sectionPosKey, entry));
    }

    /** Render-thread pull. Processes at most {@code limit} entries; returns the count processed. */
    public int drainPending(int limit) {
        int n = 0;
        while (n < limit) {
            PendingIngest p = ingestQueue.poll();
            if (p == null) break;
            if (p.entry == null) {
                evictLive(p.key);
            } else {
                ingest(p);
            }
            n++;
        }
        return n;
    }

    private void ingest(PendingIngest p) {
        // Replace (or insert) in the live table. On replace, free the prior entry's buffers.
        SectionEntry prev = live.put(p.key, p.entry);
        if (prev != null) {
            freeEntry(prev);
        } else if (regionManager != null && p.key != SectionCapture.UNKNOWN_SECTION) {
            // First time we've seen this section — allocate a slot in the region ledger so the
            // section is addressable (regionId << 8) | posInRegion for later draw dispatch.
            int sx = SectionPos.x(p.key);
            int sy = SectionPos.y(p.key);
            int sz = SectionPos.z(p.key);
            int ref = regionManager.allocateSection(sx, sy, sz);
            sectionToRegionRef.put(p.key, ref);
        }
        drained++;

        // Hand vertex bytes off to the terrain arena. Buffers are freed unconditionally after
        // staging — UploadStream has already memCopy'd the bytes into its mapped ring, so the
        // source ByteBuffers are no longer needed. On arena-full (SIZE_LIMIT) the upload silently
        // skips; eviction of far-away sections (V4 RenderSectionMixin) makes room over time.
        me.cortex.vulkium.render.Renderer renderer = me.cortex.vulkium.render.Renderer.get();
        me.cortex.vulkium.render.TerrainUploader uploader = renderer.terrainUploader();
        me.cortex.vulkium.vk.UploadStream stream = renderer.uploadStream();
        if (uploader != null && stream != null && p.key != SectionCapture.UNKNOWN_SECTION) {
            try {
                int addr = uploader.uploadSection(p.key, p.entry, stream);
                if (addr == me.cortex.vulkium.managers.util.SegmentedManager.SIZE_LIMIT) {
                    if (drained <= 8 || drained % 4096 == 0) {
                        LOGGER.warn("Terrain arena full — upload skipped for section 0x{} (drained={})",
                            Long.toHexString(p.key), drained);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Terrain upload failed for section 0x{} (continuing): {}",
                    Long.toHexString(p.key), e.getMessage());
            }
        }

        freeEntry(p.entry);
        droppedBytes += sizeOf(p.entry);
    }

    /** Packed {@code (regionId << 8) | posInRegion} for a live section, or -1 if unknown. */
    public int getRegionRef(long sectionPosKey) {
        return sectionToRegionRef.get(sectionPosKey);
    }

    /**
     * Drop a section from vulkium's live table + region ledger. Called from
     * {@code RenderSectionMixin} when MC moves or resets a RenderSection — the old section's
     * geometry is no longer valid and vulkium shouldn't keep addressing it.
     *
     * <p>May be called from any thread; internally posts the eviction to the render thread
     * for processing next frame, to avoid touching RegionManager / live from workers.
     */
    public void evict(long sectionPosKey) {
        if (sectionPosKey == SectionCapture.UNKNOWN_SECTION) return;
        ingestQueue.offer(PendingIngest.eviction(sectionPosKey));
    }

    private void evictLive(long key) {
        SectionEntry prev = live.remove(key);
        if (prev != null) freeEntry(prev);
        int ref = sectionToRegionRef.remove(key);
        if (ref != -1 && regionManager != null) {
            try {
                regionManager.removeSection(ref);
            } catch (RuntimeException e) {
                // RegionManager throws on some invariant failures that we'd rather not crash
                // over — the region+section refcounting still has edge cases (initial pass).
                LOGGER.debug("evict({}): RegionManager.removeSection threw", Long.toHexString(key), e);
            }
        }
        me.cortex.vulkium.render.TerrainUploader uploader =
            me.cortex.vulkium.render.Renderer.get().terrainUploader();
        if (uploader != null) {
            uploader.releaseSection(key);
        }
    }

    /** For future V7 consumers — the current snapshot of live sections. */
    public Long2ObjectOpenHashMap<SectionEntry> liveView() { return live; }

    public long drainedCount() { return drained; }
    public long droppedBytes() { return droppedBytes; }

    private static void freeEntry(SectionEntry e) {
        for (SectionEntry.LayerGeometry g : e.layers.values()) {
            if (g.vertexBytes != null) MemoryUtil.memFree(g.vertexBytes);
            if (g.indexBytes != null) MemoryUtil.memFree(g.indexBytes);
        }
        e.layers.clear();
    }

    private static long sizeOf(SectionEntry e) {
        long n = 0;
        for (SectionEntry.LayerGeometry g : e.layers.values()) {
            if (g.vertexBytes != null) n += g.vertexBytes.capacity();
            if (g.indexBytes != null) n += g.indexBytes.capacity();
        }
        return n;
    }

    private record PendingIngest(long key, SectionEntry entry) {
        /** Sentinel: entry=null means "drop this section from the live table". */
        static PendingIngest eviction(long key) { return new PendingIngest(key, null); }
    }
}
