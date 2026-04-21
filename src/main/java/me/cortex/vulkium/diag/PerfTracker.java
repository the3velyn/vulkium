package me.cortex.vulkium.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Lightweight per-phase nanosecond CPU timer. Tracks running stats (count, total ns, min,
 * max) for named phases; dumps averages to log on a configurable flush interval (default
 * 500ms, see {@link #flushIntervalNs}) and resets. Dormant phases skip zero-count rows.
 *
 * <p>Usage:
 * <pre>
 *   long t0 = PerfTracker.begin();
 *   doSomething();
 *   PerfTracker.end("prepareFrame", t0);
 * </pre>
 *
 * <p>Implementation: string-indexed open addressing over parallel arrays; O(1) insert +
 * lookup for a fixed small phase count, zero per-call allocation after first use. Not
 * thread-safe — call from the render thread only (all Vulkium draw hooks run there).
 *
 * <p>Overhead: two {@link System#nanoTime} calls + a short hash probe + 4 adds per
 * timed region. At typical 200+ FPS the tracker's own cost should stay under 0.5% of
 * frame time. Can be disabled at zero cost by checking {@link #isEnabled()} first.
 */
public final class PerfTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/perf");

    /** Flush interval in ns. Shorter = finer-grained timing intervals for A/B comparisons
     *  at the cost of more log volume. 500ms catches 4 windows/second — short enough to
     *  separate warmup from steady state in a 10s test run, long enough to avoid log spam. */
    private static volatile long flushIntervalNs = 500_000_000L;
    public static void setFlushIntervalNs(long ns) { flushIntervalNs = Math.max(50_000_000L, ns); }

    /** Open-addressing table. Power-of-two size, linear probe. */
    private static final int CAP = 64;
    private static final String[] names = new String[CAP];
    private static final long[] totals = new long[CAP];
    private static final long[] mins   = new long[CAP];
    private static final long[] maxes  = new long[CAP];
    private static final long[] counts = new long[CAP];

    private static long lastFlushNs = 0L;
    private static volatile boolean enabled = true;

    static {
        Arrays.fill(mins, Long.MAX_VALUE);
    }

    private PerfTracker() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean b) { enabled = b; }

    public static long begin() { return enabled ? System.nanoTime() : 0L; }

    public static void end(String phase, long start) {
        if (!enabled) return;
        long elapsed = System.nanoTime() - start;
        recordElapsed(phase, elapsed);
    }

    /**
     * Record a pre-computed elapsed time. For timers whose units are already in ns but whose
     * start/end aren't wallclock — e.g. GPU timestamp queries resolved on a frame-delayed
     * readback by {@link GpuTimerPool}. Same accumulator path as {@link #end}, so GPU and
     * CPU phases render into the same flush log with the convention that GPU phases carry
     * a {@code "gpu."} prefix in their name.
     */
    public static void recordElapsed(String phase, long elapsedNs) {
        if (!enabled) return;
        int slot = findSlot(phase);
        totals[slot] += elapsedNs;
        counts[slot]++;
        if (elapsedNs < mins[slot]) mins[slot] = elapsedNs;
        if (elapsedNs > maxes[slot]) maxes[slot] = elapsedNs;
        maybeFlush();
    }

    /** Look up or insert a slot for {@code phase} via content-hash + linear probe. Earlier
     *  versions used {@code System.identityHashCode} + {@code ==} which was ~5ns faster for
     *  string-literal callers but silently miscounted when a caller passed dynamically
     *  concatenated names (e.g. {@code "gpu." + phase} from {@link GpuTimerPool}): each
     *  concat produced a new String identity, probed to different slots, and samples ended
     *  up summed into whichever slot first collided. Content hashing makes every semantically
     *  equal name land in the same slot regardless of instance identity. */
    private static int findSlot(String phase) {
        int h = phase.hashCode() & (CAP - 1);
        for (int probes = 0; probes < CAP; probes++) {
            int slot = (h + probes) & (CAP - 1);
            if (names[slot] == null) {
                names[slot] = phase;
                return slot;
            }
            if (names[slot] == phase || names[slot].equals(phase)) return slot;
        }
        // Table full — drop sample. Should not happen with CAP=64 and <<64 distinct phases.
        return 0;
    }

    private static void maybeFlush() {
        long now = System.nanoTime();
        if (lastFlushNs == 0L) { lastFlushNs = now; return; }
        if (now - lastFlushNs < flushIntervalNs) return;
        long elapsedWindow = now - lastFlushNs;
        lastFlushNs = now;

        StringBuilder sb = new StringBuilder();
        sb.append("perf over ").append(elapsedWindow / 1_000_000L).append("ms:");
        boolean anyLogged = false;
        for (int i = 0; i < CAP; i++) {
            if (names[i] == null || counts[i] == 0) continue;
            long avgNs = totals[i] / counts[i];
            sb.append(' ').append(names[i])
              .append('=').append(avgNs / 1000L).append("µs")
              .append("(n=").append(counts[i])
              .append(", max=").append(maxes[i] / 1000L).append("µs)");
            anyLogged = true;
            // Reset bucket for next window.
            totals[i] = 0L;
            counts[i] = 0L;
            mins[i] = Long.MAX_VALUE;
            maxes[i] = 0L;
        }
        if (anyLogged) LOGGER.info(sb.toString());
    }
}
