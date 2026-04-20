package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;

/**
 * Thin wrapper around a {@code VkDescriptorPool}. Used for texture-binding paths that can't go
 * through push-descriptor (which is limited to one set per pipeline layout).
 *
 * <p>Allocated as FREE_DESCRIPTOR_SET so individual sets can be freed; size is small and fixed
 * at construction. Caller retains ownership of the allocated set handles.
 */
public final class DescriptorPool implements AutoCloseable {
    private final long handle;
    private boolean closed;

    private DescriptorPool(long handle) {
        this.handle = handle;
    }

    public long handle() { return handle; }

    public static DescriptorPool forCombinedImageSampler(int maxSets, int bindingsPerSet) {
        return forTypes(maxSets,
            new int[] { VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER },
            new int[] { maxSets * bindingsPerSet });
    }

    /** Generic pool builder — N descriptor types with matching counts, {@code maxSets} total. */
    public static DescriptorPool forTypes(int maxSets, int[] types, int[] counts) {
        if (types.length != counts.length) throw new IllegalArgumentException();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                sizes.position(i).type(types[i]).descriptorCount(counts[i]);
            }
            sizes.position(0);

            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                .maxSets(maxSets)
                .pPoolSizes(sizes);

            LongBuffer pPool = stack.callocLong(1);
            int r = VK10.vkCreateDescriptorPool(MojangVulkanBridge.vkDevice(), info, null, pPool);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorPool failed: VkResult=" + r);
            }
            return new DescriptorPool(pPool.get(0));
        }
    }

    /** Allocate one descriptor set matching {@code layout} from this pool. */
    public long allocateSet(DescriptorSetLayout layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSetLayouts = stack.longs(layout.handle());
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(handle)
                .pSetLayouts(pSetLayouts);
            LongBuffer pSet = stack.callocLong(1);
            int r = VK10.vkAllocateDescriptorSets(MojangVulkanBridge.vkDevice(), info, pSet);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkAllocateDescriptorSets failed: VkResult=" + r);
            }
            return pSet.get(0);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyDescriptorPool(MojangVulkanBridge.vkDevice(), handle, null);
    }
}
