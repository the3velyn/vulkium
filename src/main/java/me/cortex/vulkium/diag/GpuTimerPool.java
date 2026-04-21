package me.cortex.vulkium.diag;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU-side per-phase timer using {@code VK_QUERY_TYPE_TIMESTAMP} queries. Complements
 * {@link PerfTracker}'s CPU timers so the log flush interleaves {@code gpu.*} stage µs
 * alongside the CPU phase µs — needed because the bulk of vulkium's frame time is GPU or
 * driver wait, invisible to wallclock brackets.
 *
 * <h2>Frame rotation</h2>
 * {@code vkGetQueryPoolResults} returns {@code VK_NOT_READY} until the GPU has written the
 * timestamp; blocking on each frame would serialize CPU and GPU. Instead we keep {@link #NUM_POOLS}
 * query pools rotating so each frame writes into a pool older than the GPU's pipeline depth:
 * <ul>
 *   <li>{@link #beginFrame()}: CPU-side. Try to resolve any pool whose results are ready —
 *       non-blocking, skip if {@code VK_NOT_READY}. Rotate to the next pool and clear its
 *       pending-phase list.</li>
 *   <li>{@link #begin}/{@link #end}: record {@code vkCmdWriteTimestamp2KHR} into the current
 *       pool. The first {@code begin()} of each frame lazy-resets the pool via
 *       {@code vkCmdResetQueryPool} so the writes go into freshly-cleared slots.</li>
 *   <li>{@link #endFrame()}: CPU-side. Mark the active pool as ready-to-resolve (its phases
 *       are queued for the next {@code beginFrame} that finds the GPU done).</li>
 * </ul>
 *
 * <h2>Hardware gate</h2>
 * Created via {@link #createOrNull} which returns {@code null} on hardware without
 * {@code timestampComputeAndGraphics} or a zero {@code timestampPeriod}. Callers treat the
 * resulting pool as an opt-in: when null, every {@link #begin}/{@link #end} is a caller-side
 * no-op. NVIDIA RTX 3060 + driver 595.58.03 reports {@code timestampPeriod=1.0}.
 *
 * <h2>Output</h2>
 * Resolved phases are pushed into {@link PerfTracker#recordElapsed} with a {@code "gpu."}
 * prefix, so they interleave with existing CPU phases in the same log flush. Sample output:
 * <pre>
 *   perf over 500ms: prepareFrame=10µs buildHzb=25µs gpu.hzbBuild=180µs gpu.opaqueDispatch=450µs ...
 * </pre>
 */
public final class GpuTimerPool implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/gpu-timer");

    /** Number of ring-rotated query pools. With 3 frames-in-flight (see Renderer.UPLOAD_SECTION_COUNT
     *  = 6 but the GPU command queue depth is usually 2-3) three pools gives enough lag for
     *  vkGetQueryPoolResults to return non-blocking on the oldest. */
    private static final int NUM_POOLS = 3;

    /** Upper bound on begin/end pairs per frame. 32 slots = 16 phases — comfortable headroom
     *  over the current ~4 brackets. Extra slots cost only query-pool bytes. */
    private static final int MAX_SLOTS_PER_POOL = 32;

    private final VkDevice device;
    private final long[] queryPools;
    /** ns per tick (from {@code VkPhysicalDeviceLimits.timestampPeriod}). Usually 1.0 on
     *  NVIDIA, something close on AMD/Intel. */
    private final float timestampPeriodNs;

    /** Phase list captured per pool, read back when the pool's queries are ready. */
    private final List<List<Phase>> poolPhases;
    /** True iff the pool has recorded phases waiting to be read back. */
    private final boolean[] poolReady;

    /** Index of the currently-recording pool, or -1 between {@link #endFrame} and the next
     *  {@link #beginFrame}. */
    private int activePool = -1;
    /** Cursor into the active pool's query slot space. Odd values leave a pending begin
     *  without a matching end — begin/end pairing is on the caller. */
    private int nextSlot;
    /** Whether {@code vkCmdResetQueryPool} has been issued for the active pool this frame.
     *  The reset is lazy so frames that never call begin() don't emit a pointless reset cmd. */
    private boolean activePoolReset;
    /** Live pairs captured this frame. Flushed into {@link #poolPhases} on {@link #endFrame}. */
    private final List<Phase> activeList = new ArrayList<>();
    /** Map of phase name → begin slot, so {@link #end} finds its pair. */
    private final Map<String, Integer> pendingBegins = new HashMap<>();

    private long frameCounter;
    private boolean closed;

    private record Phase(String name, int beginSlot, int endSlot) {}

    private GpuTimerPool(VkDevice device, long[] queryPools, float timestampPeriodNs) {
        this.device = device;
        this.queryPools = queryPools;
        this.timestampPeriodNs = timestampPeriodNs;
        this.poolPhases = new ArrayList<>(NUM_POOLS);
        for (int i = 0; i < NUM_POOLS; i++) this.poolPhases.add(new ArrayList<>());
        this.poolReady = new boolean[NUM_POOLS];
    }

    /**
     * Allocate the query pools. Returns {@code null} on hardware/driver combos that don't
     * support timestamp queries on the graphics queue; the caller treats a null result as
     * "GPU timing unavailable" and no-ops its own begin/end calls.
     */
    public static GpuTimerPool createOrNull() {
        VkDevice device = MojangVulkanBridge.vkDevice();
        // timestampPeriod > 0 means the device exposes timestamp queries with a known tick
        // conversion. A value of 0 means "no timestamps" — seen on some software GPU impls.
        float timestampPeriod = MojangVulkanBridge.timestampPeriod();
        if (timestampPeriod <= 0f) {
            LOGGER.info("GPU timer disabled: timestampPeriod={} (hardware lacks timestamp queries)",
                timestampPeriod);
            return null;
        }
        long[] pools = new long[NUM_POOLS];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack)
                .sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(MAX_SLOTS_PER_POOL);
            LongBuffer pPool = stack.callocLong(1);
            for (int i = 0; i < NUM_POOLS; i++) {
                int r = VK10.vkCreateQueryPool(device, ci, null, pPool);
                if (r != VK10.VK_SUCCESS) {
                    // Undo partial creation.
                    for (int j = 0; j < i; j++) {
                        VK10.vkDestroyQueryPool(device, pools[j], null);
                    }
                    LOGGER.warn("GPU timer disabled: vkCreateQueryPool returned {}", r);
                    return null;
                }
                pools[i] = pPool.get(0);
            }
        }
        LOGGER.info("GPU timer pool ready: {} pools × {} slots, timestampPeriod={}ns/tick",
            NUM_POOLS, MAX_SLOTS_PER_POOL, timestampPeriod);
        return new GpuTimerPool(device, pools, timestampPeriod);
    }

    /** Called once per frame, before any {@link #begin}. Resolves ready pools into
     *  {@link PerfTracker}, rotates to the next pool, and clears per-frame state. */
    public void beginFrame() {
        if (closed) return;
        // Try to resolve every ready pool. Some GPUs return results out of order relative to
        // submit order; each pool's readiness is independent.
        for (int i = 0; i < NUM_POOLS; i++) {
            if (poolReady[i] && tryResolve(i)) {
                poolReady[i] = false;
                poolPhases.get(i).clear();
            }
        }
        int next = (int) (frameCounter % NUM_POOLS);
        frameCounter++;
        if (poolReady[next]) {
            // The pool we want to record into still has un-resolved results. This means the
            // GPU is 3+ frames behind — drop the stale data so we can reuse the pool.
            poolPhases.get(next).clear();
            poolReady[next] = false;
        }
        activePool = next;
        activePoolReset = false;
        nextSlot = 0;
        activeList.clear();
        pendingBegins.clear();
    }

    /** Record a "begin" timestamp. Must be paired with {@link #end} inside the same cmd
     *  buffer submission and the same frame. Overflow (>{@link #MAX_SLOTS_PER_POOL}/2 pairs)
     *  silently drops the sample. */
    public void begin(VkCommandBuffer cmd, String phase) {
        if (closed || activePool < 0 || nextSlot >= MAX_SLOTS_PER_POOL - 1) return;
        lazyResetIfNeeded(cmd);
        int slot = nextSlot++;
        KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd,
            VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            queryPools[activePool], slot);
        pendingBegins.put(phase, slot);
    }

    /** Record an "end" timestamp. Looks up the matching begin by name; silently no-ops if
     *  no begin was recorded (phase was skipped by overflow or a branch). */
    public void end(VkCommandBuffer cmd, String phase) {
        if (closed || activePool < 0) return;
        Integer beginSlot = pendingBegins.remove(phase);
        if (beginSlot == null || nextSlot >= MAX_SLOTS_PER_POOL) return;
        int endSlot = nextSlot++;
        KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd,
            VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            queryPools[activePool], endSlot);
        activeList.add(new Phase(phase, beginSlot, endSlot));
    }

    /** Called once per frame, after all recording. Marks the active pool as pending resolve
     *  so the next {@link #beginFrame} can try to read it back. */
    public void endFrame() {
        if (closed || activePool < 0) return;
        if (!activeList.isEmpty()) {
            List<Phase> phases = poolPhases.get(activePool);
            phases.clear();
            phases.addAll(activeList);
            poolReady[activePool] = true;
        }
        activePool = -1;
    }

    private void lazyResetIfNeeded(VkCommandBuffer cmd) {
        if (activePoolReset) return;
        VK10.vkCmdResetQueryPool(cmd, queryPools[activePool], 0, MAX_SLOTS_PER_POOL);
        activePoolReset = true;
    }

    private boolean tryResolve(int poolIdx) {
        List<Phase> phases = poolPhases.get(poolIdx);
        if (phases.isEmpty()) return true; // already drained
        int maxSlot = 0;
        for (Phase p : phases) {
            if (p.endSlot + 1 > maxSlot) maxSlot = p.endSlot + 1;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer results = stack.callocLong(maxSlot);
            int r = VK10.vkGetQueryPoolResults(
                device,
                queryPools[poolIdx],
                0, maxSlot,
                results,
                8L,  // stride: 8 bytes per 64-bit tick value
                VK10.VK_QUERY_RESULT_64_BIT);
            if (r == VK10.VK_NOT_READY) return false;
            if (r != VK10.VK_SUCCESS) {
                LOGGER.warn("vkGetQueryPoolResults pool {} returned VkResult={}", poolIdx, r);
                return true; // treat as drained so we don't spin
            }
            for (Phase p : phases) {
                long tickDelta = results.get(p.endSlot) - results.get(p.beginSlot);
                if (tickDelta < 0) continue; // monotonic violation on wrap; skip this sample
                long deltaNs = (long) (tickDelta * timestampPeriodNs);
                PerfTracker.recordElapsed("gpu." + p.name, deltaNs);
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (long pool : queryPools) {
            if (pool != 0L) {
                VK10.vkDestroyQueryPool(device, pool, (org.lwjgl.vulkan.VkAllocationCallbacks) null);
            }
        }
    }
}
