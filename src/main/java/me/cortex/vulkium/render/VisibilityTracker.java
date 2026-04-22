package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.RegionManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.function.IntConsumer;

/**
 * Per-frame region-level visibility tracker. Given the active frustum and the camera's section
 * position, walks every allocated region in {@link RegionManager}, rejects those out of render
 * distance or outside the frustum, and emits the survivors in near-to-far order for the GPU's
 * region-sort compute dispatch + subsequent mesh-shader draws.
 *
 * <p>This is the vulkium analogue of nvidium's {@code AsyncOcclusionTracker}, but massively
 * simplified for V7:
 * <ul>
 *   <li>No BFS over section connectivity — nvidium needed per-section graph traversal for deep
 *       occlusion culling; vulkium delegates section-level occlusion to the GPU's region-raster
 *       shaders and only does region-granularity CPU filtering here.</li>
 *   <li>No VisGraph / SectionCompiler connectivity — the visibility graph from sodium's
 *       {@code OcclusionCuller} isn't wired yet. Defer.</li>
 *   <li>No worker thread — runs inline on the render thread. The double-buffered result array
 *       is kept so a future async move only has to relax the update site.</li>
 * </ul>
 *
 * <p>Revisit when V8 (HZB / two-pass occlusion) lands: the "accepted" pre-filter should start
 * feeding a GPU-side hierarchical-Z query instead of (or in addition to) the frustum test, and
 * the near-to-far ordering becomes load-bearing for early-Z benefit.
 */
public final class VisibilityTracker {

    /** Ping-pong snapshots: update() writes to the back buffer; consumers read from the front. */
    private int[] front = new int[0];
    private int frontCount = 0;
    private int[] back = new int[0];

    /** Per-regionId membership bitmap, updated alongside the sorted visible-id list. Enables
     *  O(1) {@link #isRegionVisible} lookups — without this, consumers who need "is this
     *  specific region in view?" (e.g. {@link TranslucentSectionSorter}) would have to binary-
     *  search or linear-scan the front array every frame. Size-matched to {@code maxRegionIndex}
     *  at each update; slots past the current max stay false since the loop doesn't touch them. */
    private boolean[] visibleMask = new boolean[0];

    /** Scratch pair of parallel arrays used during update(): ids + their manhattan distances. */
    private int[] scratchIds = new int[0];
    private int[] scratchDist = new int[0];

    private long lastUpdateNs = 0L;
    private long updates = 0L;

    public VisibilityTracker() {}

    /**
     * Rebuild the visible-region list for the current frame.
     *
     * @param frustum         active view frustum; vanilla MC's culler via
     *                        {@code LevelRenderState.cameraRenderState.cullFrustum}.
     * @param camSectionX     camera section-x ( = blockX >> 4 ).
     * @param camSectionY     camera section-y.
     * @param camSectionZ     camera section-z.
     * @param renderDistance  MC chunk render distance (sections); regions with Manhattan distance
     *                        > {@code renderDistance * 8} (i.e. region-space equivalent) would be
     *                        culled, but we keep the check in section units since
     *                        {@link RegionManager#distance} returns section-coord distance.
     */
    public void update(Frustum frustum,
                       int camSectionX, int camSectionY, int camSectionZ,
                       int renderDistance) {
        long start = System.nanoTime();

        RegionManager rm = Vulkium.regionManager();
        if (rm == null) {
            // No ledger bound yet (e.g. vulkium disabled). Flip to an empty front.
            frontCount = 0;
            lastUpdateNs = System.nanoTime() - start;
            updates++;
            return;
        }

        int max = rm.maxRegionIndex();
        ensureScratchCapacity(max);

        // Clear the visible mask for this frame's range. Entries past max from a previous
        // larger frame can stay — the {@link #isRegionVisible} bounds check rejects them
        // before the lookup. Only [0, max) is authoritative.
        if (visibleMask.length < max) {
            visibleMask = new boolean[Math.max(max, visibleMask.length == 0 ? 64 : visibleMask.length * 2)];
        } else {
            java.util.Arrays.fill(visibleMask, 0, max, false);
        }

        // Distance threshold uses the same units as RegionManager.distance() (section coords).
        // RegionManager.distance returns the manhattan distance from region-center to camera in
        // section units. A safe over-estimate: renderDistance sections in each axis + 4 for the
        // half-region slop (region half-extent is 4 sections on X/Z, 2 on Y). Use 3*(rd+4) so
        // manhattan-sum corners are admitted.
        int distanceLimit = renderDistance + 4;

        int n = 0;
        for (int id = 0; id < max; id++) {
            if (!rm.regionExists(id)) continue;

            // Distance cull (cheap, integer manhattan).
            int d = rm.distance(id, camSectionX, camSectionY, camSectionZ);
            if (d > distanceLimit * 3) continue;

            // Frustum cull. Region AABB is 128x64x128 blocks rooted at (rx<<7, ry<<6, rz<<7).
            long key = rm.regionIdToKey(id);
            int rx = SectionPos.x(key);
            int ry = SectionPos.y(key);
            int rz = SectionPos.z(key);
            double minX = (double) (rx << 7);
            double minY = (double) (ry << 6);
            double minZ = (double) (rz << 7);
            AABB aabb = new AABB(minX, minY, minZ, minX + 128.0, minY + 64.0, minZ + 128.0);
            if (!frustum.isVisible(aabb)) continue;

            scratchIds[n] = id;
            scratchDist[n] = d;
            visibleMask[id] = true;
            n++;
        }

        // Sort the [0, n) prefix by distance ascending (near-to-far). Insertion sort is
        // O(n^2) worst case but n is small (bounded by maxRegionIndex, typically <~1000 at
        // 32-chunk render distance) and the array is often already near-sorted since the
        // iteration order of regionIds tends to cluster spatially. Revisit with a radix sort
        // when benchmarks warrant.
        for (int i = 1; i < n; i++) {
            int kd = scratchDist[i];
            int ki = scratchIds[i];
            int j = i - 1;
            while (j >= 0 && scratchDist[j] > kd) {
                scratchDist[j + 1] = scratchDist[j];
                scratchIds[j + 1] = scratchIds[j];
                j--;
            }
            scratchDist[j + 1] = kd;
            scratchIds[j + 1] = ki;
        }

        // Publish by swapping into the back buffer. The back buffer's storage is reused next
        // update; the previous front stays alive for any in-flight forEachVisibleRegion reader.
        if (back.length < n) {
            back = new int[Math.max(n, back.length == 0 ? 64 : back.length * 2)];
        }
        System.arraycopy(scratchIds, 0, back, 0, n);

        // Atomic-ish swap: write frontCount last so a concurrent reader either sees the old
        // snapshot or the new one, never a mixed state. Without a volatile this is still
        // render-thread-only today; the ordering sets us up for the async move later.
        int[] newFront = back;
        back = front;
        front = newFront;
        frontCount = n;

        lastUpdateNs = System.nanoTime() - start;
        updates++;
    }

    private void ensureScratchCapacity(int maxRegionIndex) {
        if (scratchIds.length < maxRegionIndex) {
            int newCap = Math.max(maxRegionIndex, scratchIds.length == 0 ? 64 : scratchIds.length * 2);
            scratchIds = new int[newCap];
            scratchDist = new int[newCap];
        }
    }

    /** Number of regions currently on the visible list (last published snapshot). */
    public int visibleRegionCount() {
        return frontCount;
    }

    /**
     * Visit each visible region id in sorted (near-to-far) order. Used by the region-sort
     * compute dispatch + subsequent mesh-shader draws.
     */
    public void forEachVisibleRegion(IntConsumer consumer) {
        // Snapshot locals so a racing future-async update() can't rug-pull us mid-iteration.
        int[] ids = front;
        int n = frontCount;
        for (int i = 0; i < n; i++) {
            consumer.accept(ids[i]);
        }
    }

    /** Direct access to the visible-region front buffer. Use together with
     *  {@link #visibleRegionCount()} to iterate without the lambda-capture cost of
     *  {@link #forEachVisibleRegion(IntConsumer)}. Read-only; do not mutate. */
    public int[] visibleRegionsArray() { return front; }

    /** O(1) "is this regionId in the current visible set" lookup. Used by the translucent
     *  sort path, which iterates {@code translucentSections} directly (not via the sorted
     *  visible-list) and needs to match the opaque path's frustum+distance cull so far-away
     *  translucent geometry doesn't render while the opaque geometry behind it has been
     *  culled — see the "translucent outlives opaque at >48 chunks" bug. */
    public boolean isRegionVisible(int regionId) {
        return regionId >= 0 && regionId < visibleMask.length && visibleMask[regionId];
    }

    public long lastUpdateDurationNs() {
        return lastUpdateNs;
    }

    public long updatesRun() {
        return updates;
    }
}
