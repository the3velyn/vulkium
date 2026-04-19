package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.LongBuffer;
// (LongBuffer kept — required for VkBuffer handle out-param in vmaCreateBuffer.)

/**
 * Device-local GPU-only buffer allocated against Mojang's shared VMA allocator.
 *
 * <p>Usage intent: storage buffers (SSBO / buffer-reference targets), indirect command buffers,
 * mesh data arenas. Allocated with {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
 * VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
 * VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
 * VK_BUFFER_USAGE_VERTEX_BUFFER_BIT} by default — broad enough to cover every role vulkium
 * needs. VMA picks {@code DEVICE_LOCAL} memory.
 *
 * <p>Not thread-safe; allocate/destroy on the render thread.
 */
public final class DeviceBuffer implements VkBuffer {
    private final long handle;
    private final long allocation;
    private final long size;
    private final long deviceAddress;
    private boolean closed;

    private DeviceBuffer(long handle, long allocation, long size, long deviceAddress) {
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.deviceAddress = deviceAddress;
    }

    public static DeviceBuffer allocate(long size) {
        return allocate(size,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                | VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                | VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
    }

    public static DeviceBuffer allocate(long size, int usage) {
        long vma = MojangVulkanBridge.vma();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(size)
                .usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            var allocInfo = VmaAllocationCreateInfo.calloc(stack)
                // VMA auto: picks DEVICE_LOCAL because we didn't request host mapping.
                .usage(Vma.VMA_MEMORY_USAGE_AUTO);

            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            int result = Vma.vmaCreateBuffer(vma, bufferInfo, allocInfo, pBuffer, pAllocation, null);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer (device) failed: VkResult=" + result);
            }

            long handle = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            long addr = 0L;
            if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                var info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(handle);
                addr = VK12.vkGetBufferDeviceAddress(MojangVulkanBridge.vkDevice(), info);
            }

            return new DeviceBuffer(handle, allocation, size, addr);
        }
    }

    @Override public long handle()       { return handle; }
    @Override public long allocation()   { return allocation; }
    @Override public long size()         { return size; }
    @Override public long deviceAddress() { return deviceAddress; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyBuffer(MojangVulkanBridge.vma(), handle, allocation);
    }
}
