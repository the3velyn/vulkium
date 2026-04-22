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
    /** Monotonic counter bumped on every add/remove to {@link #translucentSections}. Consumers
     *  (TranslucentSectionSorter) snapshot it alongside the camera chunk to skip re-sorting
     *  when neither the section set nor the camera has moved. */
    private int translucentVersion;
    public int translucentVersion() { return translucentVersion; }

    /** Worker → render hand-off. Unbounded; trimmed per frame by drainPending(). */
    private final ConcurrentLinkedQueue<PendingIngest> ingestQueue = new ConcurrentLinkedQueue<>();

    /** Retry queue for ingests whose upload hit arena SIZE_LIMIT. Drained back into
     *  {@link #ingestQueue} at the START of each {@link #drainPending} call so the retry
     *  is a next-frame affair, not an in-loop retry storm against a still-full arena. */
    private final ConcurrentLinkedQueue<PendingIngest> retryQueue = new ConcurrentLinkedQueue<>();

    /** Max times a single section ingest retries before we give up and free its bytes. At
     *  500-600 FPS this is ~100-120 ms of retry window — plenty for the eviction sweep to
     *  free arena slots under typical churn, and bounded enough that a truly-full arena
     *  doesn't buffer unbounded captures in memory. */
    private static final int MAX_UPLOAD_RETRIES = 60;

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

        ingestQueue.offer(PendingIngest.fresh(sectionPosKey, entry));
    }

    /** Render-thread pull. Processes at most {@code limit} entries; returns the count processed.
     *
     *  <p>Order of operations:
     *  <ol>
     *    <li>Drain the retry queue (last frame's SIZE_LIMIT misses) into the main queue so
     *        they get another shot before new captures this frame.</li>
     *    <li>Pop from the main queue up to {@code limit}. Each ingest that hits SIZE_LIMIT
     *        pushes itself onto the retry queue for next frame (bounded by
     *        {@link #MAX_UPLOAD_RETRIES}).</li>
     *  </ol>
     *  Rationale: re-enqueuing into {@link #ingestQueue} directly would loop within one
     *  {@code drainPending} call against a still-full arena, burning retries for nothing.
     *  The retry queue is a one-frame delay that lets the eviction sweep + in-flight uploads
     *  free arena slots before the next attempt. */
    public int drainPending(int limit) {
        // Move retry queue back into main queue before processing this frame.
        PendingIngest retry;
        while ((retry = retryQueue.poll()) != null) {
            ingestQueue.offer(retry);
        }

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
        final boolean isRetry = p.retryCount > 0;

        if (isRetry) {
            // Stale retry check: if a fresh capture came in for this key while we were
            // waiting in the retry queue, live.put(newEntry) already freed p.entry's
            // ByteBuffers via freeEntry(prev). Reading from them now would be a UAF.
            // Drop this retry silently — the fresh capture will go through its own upload
            // attempt in the main queue.
            if (live.get(p.key) != p.entry) {
                return;
            }
            // Live table + region slot already populated on the first attempt; skip.
        } else {
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
                    // Re-queue the ingest with a bumped retry count. Fixes the "chunks
                    // fail to load initially but F3+A fixes them" symptom: previously
                    // SIZE_LIMIT dropped the captured bytes AND left the region marked
                    // dirty with a zero section header, so the GPU rendered no geometry
                    // even after arena pressure eased. Now we hold the bytes and retry
                    // next frame until arena has space (or until MAX_UPLOAD_RETRIES gives
                    // up, which only triggers if the arena is genuinely too small for
                    // the workload — and the user sees the warning either way).
                    if (p.retryCount < MAX_UPLOAD_RETRIES) {
                        retryQueue.offer(p.withRetry());
                        return; // skip freeEntry below — the bytes are reused by the retry
                    }
                    // Fall through to freeEntry — we're giving up on this section. A later
                    // MC-driven recompile (block edit, F3+A, chunk reload) will re-capture.
                    if (drained % 4096 == 0) {
                        LOGGER.warn("Section 0x{} exhausted {} upload retries, dropping",
                            Long.toHexString(p.key), MAX_UPLOAD_RETRIES);
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
                        boolean hasTrans = translucentQuads > 0;
                        if (hasTrans ? translucentSections.add(p.key)
                                     : translucentSections.remove(p.key)) {
                            translucentVersion++;
                        }
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
                        // Meshlet face-bin cull RE-ENABLED. The Windows/NVIDIA hang this was
                        // disabled for turned out to be unrelated — it was OpaqueDispatchList
                        // and TranslucentSectionSorter both being single host-mapped BDA
                        // buffers the CPU rewrote every frame while GPU was still reading them
                        // (WAR race under MAX_SUBMITS_IN_FLIGHT=2). Fixed in 6e7857c and
                        // 908f7d3 (both triple-buffered). Face-bin classification was
                        // visually correct after 03a69d9's Y/Z axis-swap fix — the user
                        // confirmed "seems correct now!" before the separate hang chase
                        // swept this along for the ride.
                        //
                        // TerrainUploader permutes opaque quads into
                        // [+X][+Z][+Y][-X][-Z][-Y][unsigned] arena order. This write tells
                        // populateTasks how many live in each bin; it skips the 3 bins whose
                        // outward face points AWAY from the camera → ~3-of-6 bins per section
                        // dispatch zero mesh workgroups, ~35–50% reduction in emitted opaque
                        // quads on typical outdoor scenes.
                        //
                        // Bin layout (matches task_common.glsl populateTasks read order with
                        // the nvidium-inherited Y/Z axis swap — relChunkPos.y is rel-Z,
                        // relChunkPos.z is rel-Y):
                        //   ranges.x[0:16]  = +X (east face),   emits when rel-X ≤ 0
                        //   ranges.x[16:32] = +Z (south face),  emits when rel-Z ≤ 0
                        //   ranges.y[0:16]  = +Y (top),         emits when rel-Y ≤ 0
                        //   ranges.y[16:32] = -X (west face),   emits when rel-X ≥ 0
                        //   ranges.z[0:16]  = -Z (north face),  emits when rel-Z ≥ 0
                        //   ranges.z[16:32] = -Y (bottom),      emits when rel-Y ≥ 0
                        //   ranges.w[0:16]  = total opaque quads (populateTasks computes the
                        //                     unsigned-tail size as total - sum(face bins))
                        //   ranges.w[16:32] = starting offset (= 0)
                        int[] fb = up.faceBinCounts;
                        int posX = Math.min(fb[0], 0xFFFF); // east face
                        int posZ = Math.min(fb[1], 0xFFFF); // south face
                        int posY = Math.min(fb[2], 0xFFFF); // top
                        int negX = Math.min(fb[3], 0xFFFF); // west face
                        int negZ = Math.min(fb[4], 0xFFFF); // north face
                        int negY = Math.min(fb[5], 0xFFFF); // bottom
                        MemoryUtil.memPutInt(ptr + 16, posX | (posZ << 16));
                        MemoryUtil.memPutInt(ptr + 20, posY | (negX << 16));
                        MemoryUtil.memPutInt(ptr + 24, negZ | (negY << 16));
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

    /**
     * Evict every live section SYNCHRONOUSLY on the calling thread (render thread — the F3+A
     * and allChanged paths run there). Previously we queued per-section eviction events and
     * let {@link #drainPending} process them at its normal 256/frame cap — for 10k+ live
     * sections that's 40+ frames of trailing eviction work happening in parallel with MC's
     * fresh re-compile ingest, which caused a sustained FPS drop after every F3+A.
     *
     * <p>Inline eviction converts that to one big CPU spike (typically 10-50ms on 10k
     * sections) at the moment of F3+A, after which the engine is clean and the fresh ingest
     * from MC's recompile can land at full rate. Also drops any queued (stale) ingests from
     * the worker threads for these same keys — the fresh recompile will produce the correct
     * replacements.
     */
    public void queueFlushAll() {
        long[] keys = live.keySet().toLongArray();
        for (long key : keys) {
            evictLive(key);
        }
        // Drop queued worker-thread ingests that are now obsolete — they reference the arena
        // slots we just freed, and MC will re-dispatch compile tasks for the same sections
        // after resetLevelRenderData anyway. Retry queue too: its entries point at bytes
        // whose live[key] just got evicted, which would trip the stale-retry guard anyway
        // but clearing is cheaper than letting them cycle through.
        ingestQueue.clear();
        retryQueue.clear();
        resortQueue.clear();
        LOGGER.info("Flushed {} live sections inline (F3+A).", keys.length);
    }

    private void evictLive(long key) {
        SectionEntry prev = live.remove(key);
        if (prev != null) freeEntry(prev);
        // Bump translucentVersion on *every* eviction, not just those whose removed section
        // was itself translucent. RegionManager.removeSection does a tail-compaction swap —
        // if any OTHER section in the same region sat at the tail compact slot and gets
        // shifted to fill the vacated slot, its GPU-compact id changes. If that tail-section
        // was translucent, its gpuRef in the TranslucentSectionSorter cache goes stale and
        // the task shader would redirect to the wrong section. The narrow "was-translucent"
        // bump pre-translucent-cache was sufficient, but the sort-cache needs tighter
        // invalidation. Cost: one extra re-sort per opaque-only eviction, still dominated
        // by eviction I/O.
        translucentSections.remove(key);
        translucentVersion++;
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

    private record PendingIngest(long key, SectionEntry entry, int retryCount) {
        /** Sentinel: entry=null means "drop this section from the live table". */
        static PendingIngest eviction(long key) { return new PendingIngest(key, null, 0); }
        /** Normal ingest (first attempt). */
        static PendingIngest fresh(long key, SectionEntry entry) {
            return new PendingIngest(key, entry, 0);
        }
        PendingIngest withRetry() {
            return new PendingIngest(key, entry, retryCount + 1);
        }
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
     * Region-keep-distance sweep. Evicts sections vulkium no longer needs.
     *
     * <p>Two criteria, matching nvidium's three modes:
     * <ul>
     *   <li>{@code 256+} — <b>Keep All</b>: no-op. Never evict. Memory-unbounded.</li>
     *   <li>{@code 32} — <b>Vanilla</b>: evict only sections MC itself has unloaded
     *       ({@code ClientLevel.hasChunk(sx, sz) == false}). Never uses the radius, so
     *       vulkium's set == MC's set.</li>
     *   <li>Intermediate {@code (32, 256)} — evict if <b>either</b> the section is outside
     *       a square of radius {@code keepDistance+4} around the camera, <b>or</b> MC has
     *       already dropped it. Keeps more than vanilla but still bounded.</li>
     * </ul>
     *
     * <p><b>Regression fix (2026-04-20):</b> the prior version of this method evicted purely
     * by radius, which threw away sections MC still had loaded. When the player walked back
     * toward those sections MC never re-ran {@code SectionCompiler.compile} (no dirty flag
     * fired) so they stayed invisible forever. Delegating eviction to MC's hasChunk check
     * means sections only leave vulkium when MC has already unloaded them; re-approaching
     * then re-loads via MC's normal chunk-load → compile → our capture path.
     *
     * <p>Walks all live keys via a one-shot array snapshot (map-walk + evict would ConcurrentMod).
     * Budget-bound by {@code maxEvictPerCall}.
     *
     * @return number of sections evicted in this call.
     */
    public int sweepKeepDistance(int cameraChunkX, int cameraChunkZ,
                                 int keepDistance, int maxEvictPerCall) {
        if (keepDistance >= 256) return 0;
        final boolean useRadius = keepDistance > 32;
        final int radius = keepDistance + 4;
        final int radiusSq = radius * radius;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc != null ? mc.level : null;
        if (level == null) return 0;

        long[] keys = live.keySet().toLongArray();
        int evicted = 0;
        for (long key : keys) {
            if (evicted >= maxEvictPerCall) break;
            int sx = net.minecraft.core.SectionPos.x(key);
            int sz = net.minecraft.core.SectionPos.z(key);
            boolean outOfRadius = false;
            if (useRadius) {
                int dx = sx - cameraChunkX;
                int dz = sz - cameraChunkZ;
                outOfRadius = (long) dx * dx + (long) dz * dz > radiusSq;
            }
            // MC's client-side chunk presence. Returns false for chunks that were unloaded
            // (fell out of the server's streaming radius) or never loaded.
            boolean mcHasChunk = level.hasChunk(sx, sz);
            if (outOfRadius || !mcHasChunk) {
                evict(key);
                evicted++;
            }
        }
        return evicted;
    }
}
