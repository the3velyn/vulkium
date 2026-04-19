package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBindSparseInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSparseBufferMemoryBindInfo;
import org.lwjgl.vulkan.VkSparseMemoryBind;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Sparse-bound device-local buffer. Virtual-address range is reserved at construction time;
 * physical memory is committed per-page via {@link #ensureCommitted(long, long)} and released
 * via {@link #deallocate(long, long)}. Port of nvidium's {@code PersistentSparseAddressableBuffer}
 * ({@code ARB_sparse_buffer}) to Vulkan ({@code VK_BUFFER_CREATE_SPARSE_BINDING_BIT} +
 * {@code vkQueueBindSparse}).
 *
 * <p>Page granularity is taken from {@code VkMemoryRequirements.alignment} for the sparse buffer
 * (typically 64 KiB on desktop GPUs). Each committed page maps 1:1 to an individual
 * {@code vkAllocateMemory} allocation — sparse binding operates below VMA's abstraction level,
 * so we cannot reuse Mojang's allocator for page blocks.
 *
 * <p><b>Synchronization:</b> every mutating bind-sparse submission signals an internal timeline
 * semaphore. Consumers (render / compute submits) <b>must</b> wait on {@link #bindTimeline()}
 * at {@link #lastBindValue()} before reading the freshly-(un)bound range. Bind-sparse ignores
 * normal pipeline barriers; the timeline semaphore is the only correct fence. {@link #close()}
 * issues {@code vkDeviceWaitIdle} before tearing down memory blocks.
 *
 * <p>Not thread-safe; call from the render thread.
 */
public final class SparseDeviceBuffer implements VkBuffer {

    private static long alignUp(long v, long a) {
        long d = v % a;
        return d == 0 ? v : v + (a - d);
    }

    private final long handle;
    private final long virtualSize;
    private final long pageSize;
    private final long totalPages;
    private final int memoryTypeIndex;
    private final long deviceAddress;
    private final boolean hasDeviceAddress;

    /** page index -> VkDeviceMemory handle for every committed page. */
    private final TreeMap<Long, Long> committedPages = new TreeMap<>();

    private final TimelineSemaphore bindTimeline;
    private long lastBindValue;

    private boolean closed;

    private SparseDeviceBuffer(long handle, long virtualSize, long pageSize, long totalPages,
                                int memoryTypeIndex, long deviceAddress, boolean hasDeviceAddress,
                                TimelineSemaphore bindTimeline) {
        this.handle = handle;
        this.virtualSize = virtualSize;
        this.pageSize = pageSize;
        this.totalPages = totalPages;
        this.memoryTypeIndex = memoryTypeIndex;
        this.deviceAddress = deviceAddress;
        this.hasDeviceAddress = hasDeviceAddress;
        this.bindTimeline = bindTimeline;
    }

    public static SparseDeviceBuffer allocate(long virtualSize) {
        return allocate(virtualSize,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    public static SparseDeviceBuffer allocate(long virtualSize, int usage) {
        if (virtualSize <= 0) throw new IllegalArgumentException("virtualSize must be > 0");
        boolean needsAddress = (usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
        var device = MojangVulkanBridge.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK10.VK_BUFFER_CREATE_SPARSE_BINDING_BIT)
                .size(virtualSize)
                .usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.callocLong(1);
            int r = VK10.vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateBuffer (sparse) failed: VkResult=" + r);
            }
            long handle = pBuffer.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, handle, memReq);
            long pageSize = memReq.alignment();
            if (pageSize <= 0) {
                VK10.vkDestroyBuffer(device, handle, null);
                throw new RuntimeException("sparse buffer reported zero alignment");
            }

            long alignedSize = alignUp(virtualSize, pageSize);
            long totalPages = alignedSize / pageSize;

            int memoryTypeIndex = pickDeviceLocalMemoryType(memReq.memoryTypeBits());
            if (memoryTypeIndex < 0) {
                VK10.vkDestroyBuffer(device, handle, null);
                throw new RuntimeException("no DEVICE_LOCAL memory type satisfies sparse buffer requirements");
            }

            long addr = 0L;
            if (needsAddress) {
                var info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(handle);
                addr = VK12.vkGetBufferDeviceAddress(device, info);
            }

            TimelineSemaphore timeline = TimelineSemaphore.create();
            return new SparseDeviceBuffer(handle, alignedSize, pageSize, totalPages,
                memoryTypeIndex, addr, needsAddress, timeline);
        }
    }

    private static int pickDeviceLocalMemoryType(int typeBits) {
        var phys = MojangVulkanBridge.vkPhysicalDevice();
        if (phys == null) throw new IllegalStateException("physical device not captured");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(phys, props);
            int count = props.memoryTypeCount();
            int fallback = -1;
            for (int i = 0; i < count; i++) {
                if ((typeBits & (1 << i)) == 0) continue;
                int flags = props.memoryTypes(i).propertyFlags();
                if ((flags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) return i;
                if (fallback < 0) fallback = i;
            }
            return fallback;
        }
    }

    public void ensureCommitted(long offset, long length) {
        if (length <= 0) return;
        if (offset < 0 || offset + length > virtualSize) {
            throw new IllegalArgumentException("range [" + offset + ", " + (offset + length)
                + ") outside virtual size " + virtualSize);
        }
        long pStart = offset / pageSize;
        long pEnd = (offset + length + pageSize - 1) / pageSize;

        // Coalesce contiguous uncommitted pages into runs, allocate one VkDeviceMemory per run,
        // and submit a single bind-sparse with N binds.
        record Run(long firstPage, int count, long memory) {}
        List<Run> runs = new ArrayList<>();
        long runStart = -1;
        int runCount = 0;
        for (long p = pStart; p < pEnd; p++) {
            if (committedPages.containsKey(p)) {
                if (runCount > 0) {
                    runs.add(new Run(runStart, runCount, allocatePageBlock(runCount)));
                    runCount = 0;
                }
            } else {
                if (runCount == 0) runStart = p;
                runCount++;
            }
        }
        if (runCount > 0) {
            runs.add(new Run(runStart, runCount, allocatePageBlock(runCount)));
        }
        if (runs.isEmpty()) return;

        submitBinds(runs.stream()
            .map(run -> new BindEntry(run.firstPage * pageSize, (long) run.count * pageSize, run.memory))
            .toList());

        for (Run run : runs) {
            for (int i = 0; i < run.count; i++) {
                // Every page records the shared block handle; freeing requires reference-tracking
                // because a single VkDeviceMemory backs multiple pages. See deallocate().
                committedPages.put(run.firstPage + i, run.memory);
            }
        }
    }

    public void deallocate(long offset, long length) {
        if (length <= 0) return;
        if (offset < 0 || offset + length > virtualSize) {
            throw new IllegalArgumentException("range [" + offset + ", " + (offset + length)
                + ") outside virtual size " + virtualSize);
        }
        long pStart = offset / pageSize;
        long pEnd = (offset + length + pageSize - 1) / pageSize;

        // Collect runs of currently-committed pages inside the range. Unbind them in one submit,
        // then free the underlying VkDeviceMemory blocks once no page references them.
        record UnbindRun(long firstPage, int count) {}
        List<UnbindRun> runs = new ArrayList<>();
        long runStart = -1;
        int runCount = 0;
        for (long p = pStart; p < pEnd; p++) {
            if (committedPages.containsKey(p)) {
                if (runCount == 0) runStart = p;
                runCount++;
            } else if (runCount > 0) {
                runs.add(new UnbindRun(runStart, runCount));
                runCount = 0;
            }
        }
        if (runCount > 0) runs.add(new UnbindRun(runStart, runCount));
        if (runs.isEmpty()) return;

        List<BindEntry> entries = new ArrayList<>(runs.size());
        for (UnbindRun run : runs) {
            entries.add(new BindEntry(run.firstPage * pageSize, (long) run.count * pageSize, VK10.VK_NULL_HANDLE));
        }
        submitBinds(entries);

        // Track every distinct memory block whose entire page set fell inside the freed runs.
        var freedMemory = new java.util.HashSet<Long>();
        for (UnbindRun run : runs) {
            for (int i = 0; i < run.count; i++) {
                Long mem = committedPages.remove(run.firstPage + i);
                if (mem != null && mem != VK10.VK_NULL_HANDLE) freedMemory.add(mem);
            }
        }
        // A memory block may back pages outside the deallocated range; keep it alive in that case.
        for (Long mem : committedPages.values()) freedMemory.remove(mem);
        // Wait for the unbind to finish before freeing. Bind-sparse is async on the queue, so
        // freeing the memory before the GPU has dropped its binding is undefined.
        bindTimeline.awaitUntil(lastBindValue, Long.MAX_VALUE);
        var device = MojangVulkanBridge.vkDevice();
        for (Long mem : freedMemory) VK10.vkFreeMemory(device, mem, null);
    }

    private long allocatePageBlock(int pageCount) {
        var device = MojangVulkanBridge.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                .sType$Default()
                .flags(hasDeviceAddress ? VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT : 0);
            var info = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .pNext(flagsInfo.address())
                .allocationSize((long) pageCount * pageSize)
                .memoryTypeIndex(memoryTypeIndex);

            LongBuffer pMem = stack.callocLong(1);
            int r = VK10.vkAllocateMemory(device, info, null, pMem);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkAllocateMemory (sparse page block) failed: VkResult=" + r);
            }
            return pMem.get(0);
        }
    }

    private record BindEntry(long resourceOffset, long byteSize, long memory) {}

    private void submitBinds(List<BindEntry> entries) {
        long signalValue = bindTimeline.nextSignalValue();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var binds = VkSparseMemoryBind.calloc(entries.size(), stack);
            long memOffset = 0;
            long lastMem = -1;
            for (int i = 0; i < entries.size(); i++) {
                BindEntry e = entries.get(i);
                // Per-allocation bind — offset within the VkDeviceMemory is 0 for fresh allocs
                // (one alloc per run) and unused for unbinds.
                binds.get(i)
                    .resourceOffset(e.resourceOffset)
                    .size(e.byteSize)
                    .memory(e.memory)
                    .memoryOffset(0)
                    .flags(0);
                // Sanity: if two runs shared a memory block we'd need cumulative offsets; we
                // keep one alloc per run so this branch only suppresses a hypothetical future
                // change. lastMem / memOffset intentionally unused otherwise.
                if (e.memory != VK10.VK_NULL_HANDLE && e.memory == lastMem) {
                    memOffset += e.byteSize;
                    binds.get(i).memoryOffset(memOffset);
                } else {
                    memOffset = 0;
                }
                lastMem = e.memory;
            }

            var bufferBind = VkSparseBufferMemoryBindInfo.calloc(1, stack)
                .buffer(handle)
                .pBinds(binds);

            var timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                .sType$Default()
                .pSignalSemaphoreValues(stack.longs(signalValue));

            var bindInfo = VkBindSparseInfo.calloc(1, stack);
            bindInfo.get(0)
                .sType$Default()
                .pNext(timelineInfo.address())
                .pBufferBinds(bufferBind)
                .pSignalSemaphores(stack.longs(bindTimeline.handle()));

            int r = VK10.vkQueueBindSparse(MojangVulkanBridge.vkGraphicsQueue(), bindInfo, VK10.VK_NULL_HANDLE);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkQueueBindSparse failed: VkResult=" + r);
            }
        }
        lastBindValue = signalValue;
    }

    /** @return timeline semaphore signalled after each bind-sparse submission. */
    public TimelineSemaphore bindTimeline() { return bindTimeline; }

    /** @return signal value of the most recent bind-sparse submission (0 if none issued). */
    public long lastBindValue() { return lastBindValue; }

    public long committedBytes() { return (long) committedPages.size() * pageSize; }
    public long pagesCommitted() { return committedPages.size(); }
    public long pageSize() { return pageSize; }
    public long totalPages() { return totalPages; }

    @Override public long handle()        { return handle; }
    /** Always 0 — sparse pages are allocated directly via {@code vkAllocateMemory}, not VMA. */
    @Override public long allocation()    { return VK10.VK_NULL_HANDLE; }
    @Override public long size()          { return virtualSize; }
    @Override public long deviceAddress() { return deviceAddress; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var device = MojangVulkanBridge.vkDevice();
        // Flush outstanding bind-sparse work before destroying backing memory. Cheaper
        // alternatives (wait on bindTimeline + on Mojang's frame fences) require tracking
        // every consumer; vkDeviceWaitIdle is correct and only hit on shutdown.
        VK10.vkDeviceWaitIdle(device);
        var unique = new java.util.HashSet<>(committedPages.values());
        committedPages.clear();
        for (Long mem : unique) {
            if (mem != null && mem != VK10.VK_NULL_HANDLE) VK10.vkFreeMemory(device, mem, null);
        }
        VK10.vkDestroyBuffer(device, handle, null);
        bindTimeline.close();
    }
}
