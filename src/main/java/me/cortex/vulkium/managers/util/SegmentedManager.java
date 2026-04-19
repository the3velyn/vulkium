package me.cortex.vulkium.managers.util;

import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

/**
 * Sub-allocator for a contiguous address space.
 *
 * <p>Ported from nvidium. Backs region/section geometry arenas (one {@code SegmentedManager}
 * per arena, with the underlying {@code VkBuffer} managed separately).
 *
 * <p>Algorithm:
 * <ul>
 *   <li>Two red-black trees — {@code FREE} keyed by {@code (size, address)} for best-fit
 *       search, {@code TAKEN} keyed by {@code (address, size)} for free/expand lookups.</li>
 *   <li>34-bit address range (max 2^30 per allocation, 2^39 total).</li>
 *   <li>Free-block coalescing on release; shrink at the high-water mark.</li>
 * </ul>
 *
 * <p>Not thread-safe.
 */
public class SegmentedManager {
    public static final long SIZE_LIMIT = -1;

    private final int ADDR_BITS = 34;
    private final int SIZE_BITS = 64 - ADDR_BITS;
    private final long SIZE_MSK = (1L << SIZE_BITS) - 1;
    private final long ADDR_MSK = (1L << ADDR_BITS) - 1;

    private final LongRBTreeSet FREE = new LongRBTreeSet();   // key: (size << ADDR_BITS) | address
    private final LongRBTreeSet TAKEN = new LongRBTreeSet();  // key: (address << SIZE_BITS) | size

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;

    /** True if the last {@link #alloc}/{@link #free}/{@link #expand} grew/shrank the arena. */
    public boolean resized;

    public long getSize() { return totalSize; }

    public void setLimit(long size) { this.sizeLimit = size; }

    public long alloc(int size) {
        if (size == 0) throw new IllegalArgumentException();
        var iter = FREE.iterator(((long) size << ADDR_BITS) - 1);
        if (!iter.hasNext()) {
            resized = true;
            long addr = totalSize;
            if (totalSize + size > sizeLimit) return SIZE_LIMIT;
            totalSize += size;
            TAKEN.add((addr << SIZE_BITS) | (long) size);
            return addr;
        }
        long slot = iter.nextLong();
        iter.remove();
        if ((slot >>> ADDR_BITS) == size) {
            TAKEN.add((slot << SIZE_BITS) | (slot >>> ADDR_BITS));
        } else {
            TAKEN.add(((slot & ADDR_MSK) << SIZE_BITS) | size);
            FREE.add((((slot >>> ADDR_BITS) - size) << ADDR_BITS) | ((slot & ADDR_MSK) + size));
        }
        resized = false;
        return slot & ADDR_MSK;
    }

    /** @return bytes freed. */
    public int free(long addr) {
        addr &= ADDR_MSK;
        var iter = TAKEN.iterator(addr << SIZE_BITS);
        long slot = iter.nextLong();
        if (slot >> SIZE_BITS != addr) throw new IllegalStateException();
        long size = slot & SIZE_MSK;
        iter.remove();

        if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot >>> SIZE_BITS) + (prevSlot & SIZE_MSK);
            if (endAddr != addr) {
                long delta = addr - endAddr;
                FREE.remove((delta << ADDR_BITS) | endAddr);
                slot = (endAddr << SIZE_BITS) | ((slot & SIZE_MSK) + delta);
            }
            iter.nextLong();
        } else if (!FREE.isEmpty()) {
            if (FREE.remove(addr << ADDR_BITS)) {
                slot = addr + size;
            }
        }

        if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot >>> SIZE_BITS) + (slot & SIZE_MSK);
            if (endAddr != nextSlot >>> SIZE_BITS) {
                long delta = (nextSlot >>> SIZE_BITS) - endAddr;
                FREE.remove((delta << ADDR_BITS) | endAddr);
                slot = (slot & (ADDR_MSK << SIZE_BITS)) | ((slot & SIZE_MSK) + delta);
            }
        } else {
            resized = true;
            totalSize -= (slot & SIZE_MSK);
            return (int) size;
        }

        resized = false;
        slot = (slot >>> SIZE_BITS) | (slot << ADDR_BITS);
        FREE.add(slot);
        return (int) size;
    }

    /** @return true if the allocation was successfully grown in-place. */
    public boolean expand(long addr, int extra) {
        addr &= ADDR_MSK;
        var iter = TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext()) return false;
        long slot = iter.nextLong();
        if (slot >> SIZE_BITS != addr) throw new IllegalStateException();
        long updatedSlot = (slot & (ADDR_MSK << SIZE_BITS)) | ((slot & SIZE_MSK) + extra);
        resized = false;
        if (iter.hasNext()) {
            long next = iter.nextLong();
            long endAddr = (slot >>> SIZE_BITS) + (slot & SIZE_MSK);
            long delta = (next >>> SIZE_BITS) - endAddr;
            if (extra <= delta) {
                FREE.remove((delta << ADDR_BITS) | endAddr);
                iter.previousLong();
                iter.previousLong();
                iter.remove();
                TAKEN.add(updatedSlot);
                if (extra != delta) {
                    FREE.add(((delta - extra) << ADDR_BITS) | (endAddr + extra));
                }
                return true;
            }
            return false;
        }
        if (totalSize + extra > sizeLimit) return false;
        iter.remove();
        TAKEN.add(updatedSlot);
        totalSize += extra;
        resized = true;
        return true;
    }

    public long getSize(long addr) {
        addr &= ADDR_MSK;
        var iter = TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext()) throw new IllegalArgumentException();
        long slot = iter.nextLong();
        if (slot >> SIZE_BITS != addr) throw new IllegalStateException();
        return slot & SIZE_MSK;
    }
}
