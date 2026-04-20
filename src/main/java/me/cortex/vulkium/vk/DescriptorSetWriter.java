package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper to populate a {@code VkDescriptorSet} with combined-image-sampler bindings via
 * {@code vkUpdateDescriptorSets}. Runs once per set, not per-draw.
 */
public final class DescriptorSetWriter {
    private final long set;
    private final List<Binding> bindings = new ArrayList<>();

    private record Binding(int index, long view, long sampler, int imageLayout) {}

    public DescriptorSetWriter(long set) {
        this.set = set;
    }

    public DescriptorSetWriter combinedImageSampler(int binding, long imageView, long sampler, int imageLayout) {
        bindings.add(new Binding(binding, imageView, sampler, imageLayout));
        return this;
    }

    /** Apply all accumulated bindings via vkUpdateDescriptorSets. */
    public void update() {
        if (bindings.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(bindings.size(), stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                Binding b = bindings.get(i);
                images.position(i)
                    .sampler(b.sampler)
                    .imageView(b.view)
                    .imageLayout(b.imageLayout);
                writes.position(i)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(b.index)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            }
            images.position(0);
            writes.position(0);
            VK10.vkUpdateDescriptorSets(MojangVulkanBridge.vkDevice(), writes, null);
        }
    }
}
