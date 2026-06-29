package me.cortex.vulkium.managers;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Per-section packed-key → "section first observed by Sodium" wall-clock millis.
     *  Populated by {@code RenderSectionManagerEmptyMixin.onSectionAdded} the moment
     *  Sodium registers a new section (which happens on chunk-load for ALL 16 sections
     *  in a column, even all-air ones). Consumed by vulkium's ingest path on first-
     *  insert to decide whether to fade:
     *  <ul>
     *    <li>Section in map, delta &lt; fadeDuration → fade (section just arrived from
     *        MC's stream, this is the initial fade window).</li>
     *    <li>Section in map, delta ≥ fadeDuration → no fade (the section has been
     *        recognised by Sodium for longer than the fade duration; this ingest is
     *        a block-edit, not a fresh load).</li>
     *    <li>Section not in map → no fade (defensive — shouldn't happen since
     *        onSectionAdded always fires before our first ingest of a section).</li>
     *  </ul>
     *  Matches vanilla MC's per-section fade timer semantics: timer starts when the
     *  section is initialised by the renderer (chunk-load time), so block placements
     *  in long-loaded sections don't re-fade.
     *
     *  <p>Pruned on {@link #evictLive} (per-section drop) and {@link #queueFlushAll}. */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap sectionFirstSeenMs =
        new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

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

    // Silent-drop counters for the "hole in the world" diagnostic. Each drop type increments
    // its own counter; LOGGER.warn fires on first few + every Nth so steady-state isn't noisy
    // but the user gets a clear signal which path is firing.
    private final java.util.concurrent.atomic.AtomicLong unknownSectionDrops = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong regionOverflowDrops = new java.util.concurrent.atomic.AtomicLong();

    private SectionManager() {
        sectionToRegionRef.defaultReturnValue(-1);
        sectionFirstSeenMs.defaultReturnValue(Long.MIN_VALUE);
    }

    public static SectionManager get() { return INSTANCE; }

    /** Called from the render thread during init. Binds the region ledger. */
    public void bindRegionManager(RegionManager manager) {
        this.regionManager = manager;
    }

    public RegionManager regionManager() { return regionManager; }

    /**
     * Records "section first observed" wall-clock time. Called from Sodium's
     * {@code onSectionAdded} the moment Sodium creates a new RenderSection — happens
     * for every section in every chunk MC loads (including all-air ones). Matches
     * vanilla MC's per-section fade-timer-set-at-init semantics.
     *
     * <p>{@code putIfAbsent} semantics: if we somehow already have an entry (e.g. F3+A
     * raced the re-add), the older timestamp wins so we don't reset the fade clock.
     *
     * <p>Render-thread only (onSectionAdded fires from MC's chunk-load / Sodium redirect
     * paths, both render-thread). Fastutil map is not synchronised; do not call from
     * worker threads.
     */
    public void noteSectionAdded(int sectionX, int sectionY, int sectionZ) {
        long key = net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ);
        if (sectionFirstSeenMs.get(key) == sectionFirstSeenMs.defaultReturnValue()) {
            sectionFirstSeenMs.put(key, System.currentTimeMillis());
        }
    }

    /** Sodium dropped a render-section: drop OUR mirror of it too. This is the leak fix
     *  for the "arena fills very quickly at 32 RD" bug. Sodium can call onSectionRemoved
     *  for sections that are still within MC's chunk-load radius (Sodium owns its own
     *  render-distance bounding box separate from {@code ClientLevel.hasChunk}), so the
     *  per-frame {@code sweepKeepDistance} pass which gates on {@code !mcHasChunk} never
     *  reclaims them — every leaked section keeps its arena slot, its live entry, its
     *  region ref, etc. forever until F3+A. Previously vulkium worked at much larger RDs
     *  because there was no separate Sodium-side eviction signal at all; under the
     *  sodium-edition we must mirror it.
     *
     *  <p>Also clears the per-section first-seen entry so a future re-add (player walks
     *  back into range) genuinely re-fades like a fresh chunk-load. */
    public void noteSectionRemoved(int sectionX, int sectionY, int sectionZ) {
        long key = net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ);
        sectionFirstSeenMs.remove(key);
        // Queue an eviction through the standard pipeline so all the per-section state
        // (live entry, region ref, arena slot, translucent index cache, etc.) clears
        // uniformly on the render thread.
        evict(key);
    }

    /** Diagnostic counter for the sodium ingest path. Bumped on every {@link #offerFromSodium}
     *  that produced at least one non-empty layer; logged at first few + every 1024th. */
    private final AtomicLong sodiumOffers = new AtomicLong();

    /**
     * Sodium-edition worker-thread hand-off. Called from {@code ChunkBuilderMeshingTaskMixin}
     * after Sodium's build task finishes. Copies each layer's {@link NativeBuffer} into a
     * fresh memAlloc-owned ByteBuffer (Sodium recycles the source after the build task
     * returns) and queues the captured entry for the render thread.
     *
     * <p>Empty-section handling: when Sodium produces a build with NO usable geometry
     * (meshes map null/empty, or all entries null/zero-byte), we queue an eviction. This
     * happens when the user breaks the last block in a section — without the eviction
     * vulkium would keep rendering the stale pre-edit geometry forever. Sodium itself
     * emits a build with empty meshes on this path; we just translate to {@link #evict}.
     */
    /** Diagnostic counter for empty-build eviction (last-block-broken path). */
    private final AtomicLong sodiumEmptyEvicts = new AtomicLong();

    /** Diagnostic counter for fade-stamp first-insert. */
    private final AtomicLong sodiumFadeStamps = new AtomicLong();

    /** Total SIZE_LIMIT arena-full hits this session (across upload attempts including
     *  retries). Bumped from {@link #ingest} on every failure; logged at boot + every
     *  Nth for visibility on whether the arena is genuinely undersized. */
    private final AtomicLong arenaFullHits = new AtomicLong();
    /** Sections that exhausted MAX_UPLOAD_RETRIES and were dropped. */
    private final AtomicLong arenaDroppedSections = new AtomicLong();
    /** Wall-clock of the last arena-state log, for periodic dump throttling. */
    private long lastArenaStatLogMs;

    public void offerFromSodium(long sectionPosKey, ChunkBuildOutput output) {
        if (output == null) return;
        if (output.meshes == null || output.meshes.isEmpty()) {
            // Cheap guard: skip enqueueing eviction for sections we never had in our live
            // table. At chunk-load Sodium emits a synthetic empty ChunkBuildOutput for
            // EVERY all-air section (RenderSectionManager.submitBuildTask fast-path); on a
            // 32 RD chunk-load that's thousands of empty offers. Without this guard each
            // would queue a no-op eviction that still walks evictLive (live.remove, free
            // empty sectionEntry, sectionToRegionRef.remove, translucentVersion++,
            // uploader.releaseSection), spuriously bumping translucentVersion and forcing
            // TranslucentSectionSorter into a re-sort it doesn't need. Only enqueue if we
            // actually had data for this section.
            if (sectionToRegionRef.get(sectionPosKey) == sectionToRegionRef.defaultReturnValue()
                    && !live.containsKey(sectionPosKey)) {
                return;
            }
            long n = sodiumEmptyEvicts.incrementAndGet();
            if (n <= 16 || n % 64 == 0) {
                LOGGER.info("offerFromSodium EMPTY → evict #{} key=0x{} (sectionToRegionRef={})",
                    n, Long.toHexString(sectionPosKey),
                    sectionToRegionRef.get(sectionPosKey));
            }
            evict(sectionPosKey);
            return;
        }

        SectionEntry entry = null;
        for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> e : output.meshes.entrySet()) {
            BuiltSectionMeshParts parts = e.getValue();
            if (parts == null) continue;
            NativeBuffer nb = parts.getVertexData();
            if (nb == null) continue;
            ByteBuffer src = nb.getDirectBuffer();
            int remaining = src.remaining();
            if (remaining <= 0) continue;

            ByteBuffer copy = MemoryUtil.memAlloc(remaining);
            copy.put(src.duplicate()).flip();

            int[] srcSegments = parts.getVertexSegments();
            int[] segments = srcSegments != null ? srcSegments.clone() : new int[0];
            int totalVerts = 0;
            for (int i = 0; i < segments.length; i += 2) {
                totalVerts += segments[i];
            }

            if (entry == null) entry = new SectionEntry();
            entry.sodiumLayers.put(e.getKey(),
                new SectionEntry.SodiumLayerGeometry(copy, segments, totalVerts));
        }

        // No layer in the meshes map produced real bytes — semantically the same as
        // an empty build (e.g. all sub-meshes were null). Treat as eviction.
        if (entry == null) {
            long n = sodiumEmptyEvicts.incrementAndGet();
            if (n <= 16 || n % 64 == 0) {
                LOGGER.info("offerFromSodium ALL-NULL → evict #{} key=0x{} (sectionToRegionRef={})",
                    n, Long.toHexString(sectionPosKey),
                    sectionToRegionRef.get(sectionPosKey));
            }
            evict(sectionPosKey);
            return;
        }

        long n = sodiumOffers.incrementAndGet();
        if (n <= 4 || n % 1024 == 0) {
            LOGGER.info("offerFromSodium #{} key=0x{} layers={}",
                n, Long.toHexString(sectionPosKey), entry.sodiumLayers.size());
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

        // Periodic arena-pressure dump (every ~5s, cheap — one wall-clock read per
        // drain). Surfaces "vulkium silently rejecting chunks" cases where the
        // standard SIZE_LIMIT warnings get throttled out by their per-event gates.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastArenaStatLogMs >= 5000L) {
            lastArenaStatLogMs = nowMs;
            me.cortex.vulkium.render.TerrainUploader up =
                me.cortex.vulkium.render.Renderer.get().terrainUploader();
            if (up != null) {
                LOGGER.info("arena {}/{} MB ({}% used, {} live sections), queue={}/retry={}/resort={}, " +
                    "arenaFullHits={}, droppedSections={}",
                    up.arena().usedMB(), up.arena().allocatedMB(),
                    up.arena().allocatedMB() == 0 ? 0
                        : (100L * up.arena().usedMB() / up.arena().allocatedMB()),
                    live.size(),
                    ingestQueue.size(), retryQueue.size(), resortQueue.size(),
                    arenaFullHits.get(), arenaDroppedSections.get());
            }
        }

        return n;
    }

    private void ingest(PendingIngest p) {
        final boolean isRetry = p.retryCount > 0;
        // Tracks whether this ingest created a fresh section slot (first-ever upload for
        // the key). Mirrors vanilla LevelRenderer's `wasPreviouslyEmpty=true` path: only
        // first-insertions receive the chunk-fade stamp; rebuilds (block edits) keep the
        // original timestamp so they don't re-fade mid-edit. Retry-path ingests (arena hit
        // SIZE_LIMIT last attempt) leave `firstInsert=false` — the retry's eventual success
        // skips the stamp, so the uploadedTime for those sections stays at the buffer's
        // initial 0. Shader sees a huge `(currentMs - 0)` delta, clamps visibility to 1,
        // and the section simply appears without fade. Acceptable — retries are rare and
        // the visual cost is "arena-pressured sections don't fade", not a correctness issue.
        boolean firstInsert = false;

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
            } else if (p.key == SectionCapture.UNKNOWN_SECTION) {
                // Capture mixin couldn't resolve the section key — landed in `live` but with
                // no region slot, so it never gets drawn. Symptom: hole in the world.
                long drops = unknownSectionDrops.incrementAndGet();
                if (drops <= 4 || drops % 256 == 0) {
                    LOGGER.warn("UNKNOWN_SECTION ingest (no region allocation): drop #{} — capture mixin thread-local was unset", drops);
                }
            } else if (regionManager != null) {
                // First time we've seen this section — allocate a slot in the region ledger so the
                // section is addressable (regionId << 8) | posInRegion for later draw dispatch.
                int sx = SectionPos.x(p.key);
                int sy = SectionPos.y(p.key);
                int sz = SectionPos.z(p.key);
                int ref = regionManager.allocateSection(sx, sy, sz);
                if (ref == me.cortex.vulkium.managers.RegionManager.ALLOCATE_OVERFLOW) {
                    // Region ledger full. Drop this capture — its geometry is absent from the
                    // scene until a region evicts and MC's recompile path refires. Previously
                    // unhandled overflow resulted in the next idProvider.provide() returning
                    // an id past the regions[] array bound, producing either a hard OOB crash
                    // or (with the old index-unchecked buffer writes) GPU renders of stale
                    // arena content as "new" chunks — the reported "already-loaded chunks in
                    // new regions" symptom.
                    live.remove(p.key);
                    freeEntry(p.entry);
                    long drops = regionOverflowDrops.incrementAndGet();
                    if (drops <= 4 || drops % 64 == 0) {
                        LOGGER.warn("Region ledger full — dropped section 0x{} (drop #{}); raise vulkium.json maxRegions or wait for eviction",
                            Long.toHexString(p.key), drops);
                    }
                    drained++;
                    return;
                }
                sectionToRegionRef.put(p.key, ref);
                firstInsert = true;

                // First section in a brand-new region? Reset the host-visible visibility
                // readbacks for this slot. IdProvider recycles region ids, so the readback
                // byte may still carry a stale 0 (occluded) from the PREVIOUS region that
                // owned this slot — OpaqueDispatchList.build would then skip this frame's
                // new region and its sections never reach the task shader until the next
                // cull round-trip lands fresh bits (which took multiple frames + camera
                // motion on NVIDIA). Fixes the "immovable chunk slices on initial load"
                // bug in TODO.md.
                int regionId = ref >>> 8;
                if (regionManager.regionSectionCount(regionId) == 1) {
                    me.cortex.vulkium.render.Renderer.get().onRegionActivated(regionId);
                }
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
                    long hits = arenaFullHits.incrementAndGet();
                    if (hits <= 16 || hits % 256 == 0) {
                        long usedMb = uploader.arena().usedMB();
                        long capMb = uploader.arena().allocatedMB();
                        LOGGER.warn("Terrain arena full — section 0x{} skipped (hit #{}, arena {}/{}MB, retries={}/{})",
                            Long.toHexString(p.key), hits, usedMb, capMb,
                            p.retryCount, MAX_UPLOAD_RETRIES);
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
                    long dropped = arenaDroppedSections.incrementAndGet();
                    if (dropped <= 16 || dropped % 64 == 0) {
                        long usedMb = uploader.arena().usedMB();
                        long capMb = uploader.arena().allocatedMB();
                        LOGGER.warn("Section 0x{} DROPPED after {} retries (drop #{}, arena {}/{}MB) — raise terrainArenaMb or lower RD",
                            Long.toHexString(p.key), MAX_UPLOAD_RETRIES, dropped, usedMb, capMb);
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

                        // Chunk fade-in: stamp upload time on first-insert, BUT gate on
                        // the parent chunk's fade window. Matches vanilla MC's per-chunk
                        // fade behaviour — when you place a block in a previously-air
                        // subchunk of an already-loaded chunk, no fade fires. Vanilla
                        // achieves this because its per-section fade timer is initialised
                        // at chunk-load time (not at first-geometry time); vulkium only
                        // ingests sections when they have geometry, so we track per-chunk
                        // first-seen separately in {@link #chunkFirstSeenMs}.
                        //
                        // Logic: first-insert in a chunk where chunkFirstSeen is recent
                        // (within ~fadeDuration*2) → fade normally with `now`. Otherwise
                        // stamp with `chunkFirstSeen` (a value far enough in the past that
                        // the shader's visibility math clamps to 1.0 immediately).
                        //
                        // Index note: the fade slot must match what the shader reads —
                        // sectionData[(regionId<<8) | localId] uses LOCAL-ID (dense, via
                        // Region.pos2id), not the pos-key that allocateSection returns in
                        // `ref`. OpaqueDispatchList writes (regionId<<8)|compactId with
                        // compactId==localId, and the task shader indexes fadeTimes with
                        // the same redirected sectionId. Translate ref → localId with
                        // getSectionRefId before stamping.
                        if (firstInsert) {
                            me.cortex.vulkium.render.SectionFadeTimes ft =
                                renderer.fadeTimes();
                            if (ft != null) {
                                int localId = regionManager.getSectionRefId(ref);
                                int compactRef = ((ref >>> 8) << 8) | (localId & 0xFF);
                                long now = System.currentTimeMillis();
                                long firstSeen = sectionFirstSeenMs.get(p.key);
                                int fadeDurationMs = renderer.fadeDurationMs();
                                int stampMs;
                                if (firstSeen == sectionFirstSeenMs.defaultReturnValue()) {
                                    // Defensive: Sodium's onSectionAdded should have fired before
                                    // our first ingest. If it didn't, treat as a fresh section
                                    // and fade from now. Records the time so later ingests for
                                    // the same key see a sensible firstSeen.
                                    sectionFirstSeenMs.put(p.key, now);
                                    stampMs = (int) now;
                                } else if (now - firstSeen < fadeDurationMs) {
                                    // Section was registered with Sodium recently (initial chunk
                                    // load or F3+A) — fade by stamping with firstSeen. Shader
                                    // elapsed = now - firstSeen ∈ [0, fadeDuration) → fade
                                    // animates from wherever within the window we landed.
                                    stampMs = (int) firstSeen;
                                } else {
                                    // Section has been registered with Sodium for longer than
                                    // fadeDuration — this ingest is a block-edit re-population
                                    // of a previously-empty section in a long-loaded chunk.
                                    // Stamp far enough in the past to guarantee shader
                                    // visibility=1 with margin.
                                    stampMs = (int) (now - (long) fadeDurationMs * 2L);
                                }
                                ft.stamp(compactRef, stampMs);
                                long n = sodiumFadeStamps.incrementAndGet();
                                if (n <= 16 || n % 256 == 0) {
                                    LOGGER.info("fade-stamp #{} key=0x{} compactRef=0x{} t={} (sectionFirstSeen={}, delta={}ms, fadeDur={})",
                                        n, Long.toHexString(p.key), Integer.toHexString(compactRef),
                                        stampMs, firstSeen, now - firstSeen, fadeDurationMs);
                                }
                            }
                        }
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
        // F3+A is a "redraw everything from scratch" event — the user expects the world
        // to re-fade as the chunks rebuild. Wipe per-section first-seen tracking so each
        // re-ingest behaves as a fresh load. Sodium's own onSectionAdded will re-populate
        // entries as it re-registers sections during MC's allChanged-driven re-stream.
        sectionFirstSeenMs.clear();
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
        for (SectionEntry.SodiumLayerGeometry g : e.sodiumLayers.values()) {
            if (g.vertexBytes != null) MemoryUtil.memFree(g.vertexBytes);
        }
        e.sodiumLayers.clear();
    }

    private static long sizeOf(SectionEntry e) {
        long n = 0;
        for (SectionEntry.SodiumLayerGeometry g : e.sodiumLayers.values()) {
            if (g.vertexBytes != null) n += g.vertexBytes.capacity();
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
     *   <li>{@code 32} — <b>Vanilla</b>: evict sections outside a radius of
     *       {@code keepDistance+4 = 36} chunks around the camera, OR whose owning chunk
     *       MC has already unloaded. Matches vanilla MC's default render distance
     *       envelope.</li>
     *   <li>Intermediate {@code (32, 256)} — same logic with a larger radius. Keeps more
     *       than vanilla but still bounded.</li>
     * </ul>
     *
     * <p><b>How the "come back and it's still visible" case works:</b> when eviction fires
     * via the radius (not via MC unloading the chunk), we also call
     * {@code SectionUpdateTracker.setDirty(sx, sy, sz, true)} on MC's extractor. MC's
     * section-compile pipeline then re-queues a compile next time that section becomes
     * visible — which re-fires our capture path and the entry lands back in {@code live}.
     * Without this, MC's warm {@code SectionMesh} cache makes it think the section is
     * already ready, no compile fires, and our live map never repopulates → permanent
     * hole. This was the 2026-04-20 regression.
     *
     * <p>Walks all live keys via a one-shot array snapshot (map-walk + evict would ConcurrentMod).
     * Budget-bound by {@code maxEvictPerCall}.
     *
     * @return number of sections evicted in this call.
     */
    public int sweepKeepDistance(int cameraChunkX, int cameraChunkZ,
                                 int keepDistance, int maxEvictPerCall) {
        if (keepDistance >= 256) return 0;
        // Radius check now fires at every setting below "Keep All" — the previous
        // ">32 only" gate left "Vanilla" (32) functionally identical to "Keep All" on the
        // integrated server (where ClientLevel.hasChunk stays true for chunks the server
        // cooperates to keep loaded). Evicting at radius + marking-dirty below gives MC a
        // path to re-compile when the player returns.
        final int radius = keepDistance + 4;
        final int radiusSq = radius * radius;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc != null ? mc.level : null;
        if (level == null) return 0;

        // Resolve MC's SectionUpdateTracker once per sweep — LevelExtractor holds it
        // privately, so we reach it via an accessor mixin. Null-guarded: during early
        // boot / world-transition, levelExtractor may not be attached yet; skip setDirty
        // in that case and rely on MC's own chunk-load path to re-compile once it spins up.
        net.minecraft.client.SectionUpdateTracker tracker = null;
        try {
            if (mc != null && mc.levelExtractor != null) {
                tracker = ((me.cortex.vulkium.mixin.chunk.LevelExtractorAccessor)
                    (Object) mc.levelExtractor).vulkium$getSectionUpdateTracker();
            }
        } catch (Throwable ignored) {
            // Accessor not wired or cast failed — proceed without the re-request signal.
        }

        long[] keys = live.keySet().toLongArray();
        int evicted = 0;
        for (long key : keys) {
            if (evicted >= maxEvictPerCall) break;
            int sx = net.minecraft.core.SectionPos.x(key);
            int sy = net.minecraft.core.SectionPos.y(key);
            int sz = net.minecraft.core.SectionPos.z(key);
            int dx = sx - cameraChunkX;
            int dz = sz - cameraChunkZ;
            boolean outOfRadius = (long) dx * dx + (long) dz * dz > radiusSq;
            // MC's client-side chunk presence. Returns false for chunks that were unloaded
            // (fell out of the server's streaming radius) or never loaded.
            boolean mcHasChunk = level.hasChunk(sx, sz);
            if (outOfRadius || !mcHasChunk) {
                evict(key);
                evicted++;
                // Per-section first-seen pruning happens in noteSectionRemoved (driven by
                // Sodium's onSectionRemoved hook), not here — sweep is for vulkium-side
                // arena reclamation only.
                // Re-request compile when the player approaches again. MC won't re-run
                // SectionCompiler for chunks whose SectionMesh cache is still warm — so
                // without this, walking back toward an evicted section leaves a permanent
                // hole (regression documented in the old code's 2026-04-20 comment).
                // setDirty with neighborChange=true covers both the single-section dirty
                // and its immediate-neighbor invalidations that MC uses to know when to
                // re-run occlusion + compile.
                if (tracker != null && mcHasChunk) {
                    try {
                        tracker.setDirty(sx, sy, sz, true);
                    } catch (Throwable ignored) {
                        // Defensive — if setDirty itself races with a world-change, the
                        // caller's allChanged path will catch up.
                    }
                }
            }
        }
        return evicted;
    }
}
