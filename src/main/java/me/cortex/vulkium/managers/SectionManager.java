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

    /** Live translucent-section keys (membership = "this section has translucent quads"). The
     *  {@code SectionEntry}'s layer map is wiped after ingest to reclaim worker-thread byte
     *  buffers, so the translucent sorter can't infer translucency from the live entry. This
     *  set is the authoritative CPU-side answer. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet translucentSections =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Worker → render hand-off. Unbounded; trimmed per frame by drainPending(). */
    private final ConcurrentLinkedQueue<PendingIngest> ingestQueue = new ConcurrentLinkedQueue<>();

    /** Separate queue for POV-driven translucent resorts captured from MC's
     *  {@code ResortTransparencyTask} via {@code RenderSectionResortMixin}. Processed each
     *  frame alongside the main ingest drain. Kept separate so the per-frame drain cap on
     *  ingests doesn't starve resorts (they're cheaper — just a permutation + re-upload). */
    private final ConcurrentLinkedQueue<PendingResort> resortQueue = new ConcurrentLinkedQueue<>();

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
                me.cortex.vulkium.render.TerrainUploader.UploadResult up =
                    uploader.uploadSectionSplit(p.key, p.entry, stream);
                int addr = up.addr;
                if (addr == me.cortex.vulkium.managers.util.SegmentedManager.SIZE_LIMIT) {
                    if (drained <= 8 || drained % 4096 == 0) {
                        LOGGER.warn("Terrain arena full — upload skipped for section 0x{} (drained={})",
                            Long.toHexString(p.key), drained);
                    }
                } else if (regionManager != null) {
                    // Populate the section's 32-byte meta slab in RegionManager's sectionBuffer
                    // so the task + mesh shaders can locate this section's quads. Layout from
                    // scene.glsl's Section struct:
                    //   header.x: offsetx(0-3) sizex(4-7) chunkX(8-31, 24-bit signed)
                    //   header.y: offsetz(0-3) sizez(4-7) chunkZ(8-31) + post-sort local id (18-25)
                    //   header.z: offsety(0-3) sizey(4-7) chunkY(8-15) + hide-bit(17)
                    //   header.w: quad offset (= TerrainUploader's returned addr)
                    //   renderRanges.xyz: per-face packed (offset,delta); zero → no face culling
                    //   renderRanges.w:   low 16 bits = total unsigned quad count (task shader
                    //                     emits one bin covering the whole range)
                    int sx = SectionPos.x(p.key);
                    int sy = SectionPos.y(p.key);
                    int sz = SectionPos.z(p.key);
                    int ref = sectionToRegionRef.get(p.key);
                    if (ref != -1) {
                        int opaqueQuads = Math.min(up.opaqueQuadCount, 0xFFFF);
                        int translucentQuads = Math.min(up.translucentQuadCount, 0xFFFF);
                        // Keep the translucent-keys set in sync with actual translucent content.
                        if (translucentQuads > 0) translucentSections.add(p.key);
                        else translucentSections.remove(p.key);
                        long ptr = regionManager.setSectionData(ref);
                        // header.xyz — chunk coords + face AABB. header.z packs translucent
                        // quad count into bits 16-31 (bits 0-15 = chunk y + offset/size; bit
                        // 17 was "hide-bit"). Using the high 16 bits avoids clobbering
                        // populateTasks' `fr` starting-offset read of ranges.w>>16.
                        MemoryUtil.memPutInt(ptr,      (sx << 8) | 0xF0);
                        MemoryUtil.memPutInt(ptr +  4, (sz << 8) | 0xF0);
                        // header.z low 16 = sy<<8|size; high bits 18-31 carry translucentQuads
                        // (14 bits = max 16383). Mask sy<<8 to bits 8-16 so negative sign-extension
                        // doesn't clobber bits 17+.
                        int syBits = (sy << 8) & 0x0001FF00;
                        MemoryUtil.memPutInt(ptr +  8,
                            0xF0 | syBits | ((translucentQuads & 0x3FFF) << 18));
                        MemoryUtil.memPutInt(ptr + 12, addr);
                        // renderRanges.w low 16 = opaque quad count (consumed by populateTasks'
                        // unsigned-bin path). High 16 stays 0 so `fr = ranges.w>>16` = 0.
                        MemoryUtil.memPutInt(ptr + 16, 0);
                        MemoryUtil.memPutInt(ptr + 20, 0);
                        MemoryUtil.memPutInt(ptr + 24, 0);
                        MemoryUtil.memPutInt(ptr + 28, opaqueQuads);
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
        translucentSections.remove(key);
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

    /** Keys of live sections that have translucent quads. Maintained at ingest / evict time
     *  (the underlying byte buffers are freed immediately after upload, so layer membership
     *  can't be checked on the {@code SectionEntry} after the fact). Consumers — mainly the
     *  {@link me.cortex.vulkium.render.TranslucentSectionSorter} — should iterate this
     *  directly and call {@link #getRegionRef(long)} to resolve each key. Read-only; mutating
     *  the returned set is UB. */
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet translucentSectionKeys() {
        return translucentSections;
    }

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

    /** Worker-thread hand-off of a POV-resorted translucent index buffer. The raw bytes
     *  are a COPY (MC's ByteBuffer is closed right after our mixin returns, so we must
     *  snapshot before queueing). */
    private record PendingResort(long key, byte[] indexBytes) {}

    /** Called by RenderSectionResortMixin on the worker thread. Copies the bytes so MC can
     *  close its own buffer immediately. */
    public void offerResort(long sectionPosKey, byte[] indexBytesCopy) {
        if (sectionPosKey == SectionCapture.UNKNOWN_SECTION) return;
        resortQueue.offer(new PendingResort(sectionPosKey, indexBytesCopy));
    }

    /** Render-thread pass at frame start. Walks any queued resorts and applies them to the
     *  arena via {@code TerrainUploader.resortTranslucent}. Cheap — at most one staging-ring
     *  upload per resort, bounded by the number of translucent sections that MC re-sorted
     *  this frame (typically 0-few dozen on POV threshold crossings). */
    public int drainResorts(int limit) {
        // Gate on the user-configurable sorting level. NONE/SECTIONS both skip the per-quad
        // POV resort; only QUADS applies it. When gated off, we still need to drain the queue
        // (the worker-side mixin is always-on — easier to drop here than to add a config
        // check on the worker thread) so it doesn't grow unbounded as the player moves.
        me.cortex.vulkium.config.TranslucencySortingLevel level =
            me.cortex.vulkium.VulkiumConfig.get().translucencySortingLevel;
        if (level != me.cortex.vulkium.config.TranslucencySortingLevel.QUADS) {
            int dropped = 0;
            while (resortQueue.poll() != null) dropped++;
            return dropped;
        }
        me.cortex.vulkium.render.Renderer renderer = me.cortex.vulkium.render.Renderer.get();
        me.cortex.vulkium.render.TerrainUploader uploader = renderer.terrainUploader();
        me.cortex.vulkium.vk.UploadStream stream = renderer.uploadStream();
        if (uploader == null || stream == null) return 0;
        int n = 0;
        while (n < limit) {
            PendingResort p = resortQueue.poll();
            if (p == null) break;
            try {
                uploader.resortTranslucent(p.key, p.indexBytes, stream);
            } catch (RuntimeException ex) {
                LOGGER.warn("Translucent resort failed for section 0x{} (continuing): {}",
                    Long.toHexString(p.key), ex.getMessage());
            }
            n++;
        }
        return n;
    }

    /**
     * Region-keep-distance sweep. Matches nvidium's {@code region_keep_distance} semantics:
     * evict live sections whose chunk X/Z is outside a square of radius {@code keepDistance+4}
     * around {@code (cameraChunkX, cameraChunkZ)}. The +4 slack mirrors nvidium's
     * {@code RenderPipeline.java:198} — it avoids evict-thrash at the exact RD boundary.
     *
     * <p>Special values (same as nvidium):
     * <ul>
     *   <li>{@code 32} — rely on MC's own unload to evict (this method early-returns; MC's
     *       RenderSection rotating cache handles it via CompiledSectionMesh replacement).</li>
     *   <li>{@code 256} — keep-all: this method is a no-op.</li>
     * </ul>
     *
     * <p>Walks all live keys via a one-shot array snapshot (map-walk + evict would ConcurrentMod).
     * Budget-bound by {@code maxEvictPerCall} so a huge live set doesn't stall the render
     * thread — remaining work picks up on the next call.
     *
     * @return number of sections evicted in this call.
     */
    public int sweepKeepDistance(int cameraChunkX, int cameraChunkZ,
                                 int keepDistance, int maxEvictPerCall) {
        if (keepDistance == 32 || keepDistance >= 256) return 0;
        final int radius = keepDistance + 4;
        final int radiusSq = radius * radius;
        // Snapshot keys so eviction can safely mutate the live map inside the loop.
        long[] keys = live.keySet().toLongArray();
        int evicted = 0;
        for (long key : keys) {
            if (evicted >= maxEvictPerCall) break;
            int sx = net.minecraft.core.SectionPos.x(key);
            int sz = net.minecraft.core.SectionPos.z(key);
            int dx = sx - cameraChunkX;
            int dz = sz - cameraChunkZ;
            if (dx * dx + dz * dz > radiusSq) {
                evict(key);
                evicted++;
            }
        }
        return evicted;
    }
}
