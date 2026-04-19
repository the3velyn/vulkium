package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timeline semaphore wrapper. Replaces nvidium's {@code GlFence} + OpenGL sync objects.
 *
 * <p>Timeline semaphores are the canonical Vulkan 1.2 synchronization primitive for ordered
 * dependencies between the upload queue and the render queue (and between frames). Each submit
 * that signals this semaphore increments the counter; CPU waits on {@code counter >= N} with
 * {@code vkWaitSemaphores}.
 *
 * <p>Not thread-safe for creation/destruction; {@link #nextSignalValue()} is atomic so signal
 * tickets can be taken from worker threads.
 */
public final class TimelineSemaphore implements AutoCloseable {
    private final long handle;
    private final AtomicLong nextValue = new AtomicLong(1L);
    private boolean closed;

    private TimelineSemaphore(long handle) {
        this.handle = handle;
    }

    public static TimelineSemaphore create() {
        return create(0L);
    }

    public static TimelineSemaphore create(long initialValue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                .sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                .initialValue(initialValue);

            var createInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(typeInfo.address());

            LongBuffer pSemaphore = stack.callocLong(1);
            int result = VK12.vkCreateSemaphore(MojangVulkanBridge.vkDevice(), createInfo, null, pSemaphore);
            if (result != 0 /* VK_SUCCESS */) {
                throw new RuntimeException("vkCreateSemaphore (timeline) failed: VkResult=" + result);
            }
            return new TimelineSemaphore(pSemaphore.get(0));
        }
    }

    public long handle() { return handle; }

    /** @return current counter value as observed by the device. */
    public long currentValue() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            VK12.vkGetSemaphoreCounterValue(MojangVulkanBridge.vkDevice(), handle, out);
            return out.get(0);
        }
    }

    /** Non-blocking poll. */
    public boolean hasReached(long value) {
        return currentValue() >= value;
    }

    /**
     * Block until the semaphore's counter reaches {@code value} (or timeout). Returns true on
     * signal, false on timeout.
     */
    public boolean awaitUntil(long value, long timeoutNs) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkSemaphoreWaitInfo.calloc(stack)
                .sType$Default()
                .flags(0)
                .pSemaphores(stack.longs(handle))
                .pValues(stack.longs(value));
            int result = VK12.vkWaitSemaphores(MojangVulkanBridge.vkDevice(), info, timeoutNs);
            return result == 0; // VK_SUCCESS
        }
    }

    /**
     * Atomically reserve the next signal value. Submit record semantics:
     * {@code long tag = sem.nextSignalValue();} then signal-value-{@code tag} in the submit.
     * Later waiters {@link #awaitUntil(long, long) awaitUntil(tag, ...)}.
     */
    public long nextSignalValue() {
        return nextValue.getAndIncrement();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK12.vkDestroySemaphore(MojangVulkanBridge.vkDevice(), handle, null);
    }
}
