package me.cortex.vulkium.vk;

/**
 * Base interface for every {@code VkBuffer} vulkium allocates against Mojang's shared VMA
 * allocator. Concrete subtypes cover device-only, persistently host-mapped staging, and
 * (eventually) sparse-bound terrain arenas.
 */
public interface VkBuffer extends AutoCloseable {

    /** @return native {@code VkBuffer} handle (long, treated as unsigned). */
    long handle();

    /** @return native VMA {@code VmaAllocation} handle. */
    long allocation();

    /** @return size in bytes. */
    long size();

    /**
     * @return GPU device address, or {@code 0} if the buffer wasn't allocated with
     *     {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}.
     */
    long deviceAddress();

    @Override
    void close();
}
