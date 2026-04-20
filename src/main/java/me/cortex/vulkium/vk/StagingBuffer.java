package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Host-visible, persistently mapped upload buffer. Vulkium writes chunk vertex/index bytes into
 * this from worker threads via {@link #mapped()}, then records a {@code vkCmdCopyBuffer} from
 * here to a {@link DeviceBuffer} on the render thread.
 *
 * <p>VMA flags: {@code HOST_ACCESS_SEQUENTIAL_WRITE_BIT | MAPPED_BIT}. Host-coherent memory is
 * preferred; explicit flushes via {@link #flush(long, long)} cover the non-coherent case.
 */
public final class StagingBuffer implements VkBuffer {
    private final long handle;
    private final long allocation;
    private final long size;
    private final ByteBuffer mapped;
    private final long mappedPointer;
    private final long deviceAddress;
    private boolean closed;

    private StagingBuffer(long handle, long allocation, long size, ByteBuffer mapped,
                          long mappedPointer, long deviceAddress) {
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.mapped = mapped;
        this.mappedPointer = mappedPointer;
        this.deviceAddress = deviceAddress;
    }

    public static StagingBuffer allocate(long size) {
        return allocate(size,
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            false);
    }

    /** Persistent-mapped staging that ALSO exposes a shader device address. Use this for small
     *  per-frame buffers the shader reads directly via {@code GL_EXT_buffer_reference} — like
     *  the translucent section-sort list — so we don't have to round-trip through UploadStream. */
    public static StagingBuffer allocateHostMappedBda(long size) {
        return allocate(size,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            true);
    }

    private static StagingBuffer allocate(long size, int usage, boolean wantDeviceAddress) {
        long vma = MojangVulkanBridge.vma();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(size)
                // UNIFORM_BUFFER_BIT lets SceneUniform bind this buffer directly as a UBO
                // via vkCmdBindDescriptorSets (no intermediate device buffer). Without it,
                // descriptor-set binding is invalid and triggers GPU hangs on NVIDIA.
                .usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            var allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                    | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            var info = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(vma, bufferInfo, allocInfo, pBuffer, pAllocation, info);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer (staging) failed: VkResult=" + result);
            }

            long handle = pBuffer.get(0);
            long allocation = pAllocation.get(0);
            long mappedPointer = info.pMappedData();
            if (mappedPointer == MemoryUtil.NULL) {
                Vma.vmaDestroyBuffer(vma, handle, allocation);
                throw new RuntimeException("Staging buffer is not host-mapped — VMA didn't honour MAPPED_BIT");
            }
            long devAddr = 0L;
            if (wantDeviceAddress) {
                var addrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(handle);
                devAddr = VK12.vkGetBufferDeviceAddress(MojangVulkanBridge.vkDevice(), addrInfo);
            }
            // Java ByteBuffer view over the mapped range for ergonomic writes.
            ByteBuffer mapped = MemoryUtil.memByteBuffer(mappedPointer, (int) Math.min(size, Integer.MAX_VALUE));
            return new StagingBuffer(handle, allocation, size, mapped, mappedPointer, devAddr);
        }
    }

    /** @return raw pointer to the mapped host region. Use {@link MemoryUtil} to write at offsets. */
    public long mappedPointer() { return mappedPointer; }

    /** @return a Java {@link ByteBuffer} view over the first ≤2GB of the mapped region. */
    public ByteBuffer mapped() { return mapped; }

    /** Flush a host write range so the device can see it (no-op on coherent memory). */
    public void flush(long offset, long length) {
        Vma.vmaFlushAllocation(MojangVulkanBridge.vma(), allocation, offset, length);
    }

    @Override public long handle()       { return handle; }
    @Override public long allocation()   { return allocation; }
    @Override public long size()         { return size; }

    /** Non-zero only when created via {@link #allocateHostMappedBda(long)}; the default
     *  {@link #allocate(long)} factory does not request SHADER_DEVICE_ADDRESS_BIT. */
    @Override public long deviceAddress() { return deviceAddress; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyBuffer(MojangVulkanBridge.vma(), handle, allocation);
    }
}
