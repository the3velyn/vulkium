package me.cortex.vulkium.managers.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-frame tick dispatch. Ported from nvidium but generalized to accept any {@link Tickable}
 * instead of the hard-coded upload/download stream pair, since vulkium's streams will be VK-
 * backed rather than GL.
 *
 * <p>Not thread-safe — {@link #register} / {@link #unregister} / {@link #tickAll} must all
 * happen on the render thread.
 */
public final class TickableManager {
    private static final Set<Tickable> TICKABLES = new LinkedHashSet<>();

    private TickableManager() {}

    public static void register(Tickable t) { TICKABLES.add(t); }

    public static void unregister(Tickable t) { TICKABLES.remove(t); }

    /** Tick every registered element. Safe against concurrent modification via iterator copy. */
    public static void tickAll() {
        // Iterate a snapshot so a ticked element can safely unregister itself.
        for (Tickable t : TICKABLES.toArray(new Tickable[0])) {
            t.tick();
        }
    }
}
