package me.cortex.vulkium.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Lightweight per-phase nanosecond CPU timer. Tracks running stats (count, total ns, min,
 * max) for named phases; dumps averages to log every {@value #FLUSH_INTERVAL_NS} (~2 seconds
 * of wall clock) and resets. Dormant phases skip zero-count rows.
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

    /** Every ~2s of render-thread wall clock, flush stats to the log and reset. */
    private static final long FLUSH_INTERVAL_NS = 2_000_000_000L;

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
        int slot = findSlot(phase);
        totals[slot] += elapsed;
        counts[slot]++;
        if (elapsed < mins[slot]) mins[slot] = elapsed;
        if (elapsed > maxes[slot]) maxes[slot] = elapsed;
        maybeFlush();
    }

    /** Look up or insert a slot for {@code phase} via identity-hash + linear probe. String
     *  interning by the JVM keeps identity hashes stable for literal names used by callers. */
    private static int findSlot(String phase) {
        int h = System.identityHashCode(phase) & (CAP - 1);
        for (int probes = 0; probes < CAP; probes++) {
            int slot = (h + probes) & (CAP - 1);
            if (names[slot] == null) {
                names[slot] = phase;
                return slot;
            }
            if (names[slot] == phase) return slot;
        }
        // Table full — drop sample. Should not happen with CAP=64 and <<64 distinct phases.
        return 0;
    }

    private static void maybeFlush() {
        long now = System.nanoTime();
        if (lastFlushNs == 0L) { lastFlushNs = now; return; }
        if (now - lastFlushNs < FLUSH_INTERVAL_NS) return;
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
