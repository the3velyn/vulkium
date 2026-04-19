package me.cortex.vulkium.managers.util;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;

/**
 * Compact ID allocator with coalescing on release. Ported verbatim from nvidium.
 *
 * <p>Provides monotonically increasing IDs from {@link #provide()}; freed IDs are reused.
 * When the highest-valued IDs are released, the internal cursor shrinks so {@link #maxIndex()}
 * stays tight around live IDs.
 */
public class IdProvider {
    private int cid = 0;
    private final IntSortedSet free = new IntAVLTreeSet(Integer::compareTo);

    public int provide() {
        if (free.isEmpty()) {
            return cid++;
        }
        int ret = free.firstInt();
        free.remove(ret);
        return ret;
    }

    public void release(int id) {
        free.add(id);
        while (!free.isEmpty() && free.lastInt() + 1 == cid) {
            free.remove(--cid);
        }
    }

    public int maxIndex() {
        return cid;
    }
}
