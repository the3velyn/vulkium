package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.ArrayList;
import java.util.List;

public final class DescriptorSetWriter {
    private final long set;
    private final List<Binding> bindings = new ArrayList<>();

    private sealed interface Binding {
        int index();
    }
    private record ImageBinding(int index, long view, long sampler, int imageLayout) implements Binding {}
    private record BufferBinding(int index, long buffer, long offset, long range) implements Binding {}

    public DescriptorSetWriter(long set) {
        this.set = set;
    }

    public DescriptorSetWriter combinedImageSampler(int binding, long imageView, long sampler, int imageLayout) {
        bindings.add(new ImageBinding(binding, imageView, sampler, imageLayout));
        return this;
    }

    public DescriptorSetWriter uniformBuffer(int binding, long buffer, long offset, long range) {
        bindings.add(new BufferBinding(binding, buffer, offset, range));
        return this;
    }

    public void update() {
        if (bindings.isEmpty()) return;
        int imgCount = 0, bufCount = 0;
        for (Binding b : bindings) { if (b instanceof ImageBinding) imgCount++; else bufCount++; }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer images = imgCount > 0 ? VkDescriptorImageInfo.calloc(imgCount, stack) : null;
            VkDescriptorBufferInfo.Buffer buffers = bufCount > 0 ? VkDescriptorBufferInfo.calloc(bufCount, stack) : null;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
            int imgIdx = 0, bufIdx = 0;
            for (int i = 0; i < bindings.size(); i++) {
                Binding b = bindings.get(i);
                writes.position(i)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(b.index())
                    .dstArrayElement(0)
                    .descriptorCount(1);
                if (b instanceof ImageBinding ib) {
                    images.position(imgIdx)
                        .sampler(ib.sampler())
                        .imageView(ib.view())
                        .imageLayout(ib.imageLayout());
                    writes.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.create(images.address(imgIdx), 1));
                    imgIdx++;
                } else if (b instanceof BufferBinding bb) {
                    buffers.position(bufIdx)
                        .buffer(bb.buffer())
                        .offset(bb.offset())
                        .range(bb.range());
                    writes.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.create(buffers.address(bufIdx), 1));
                    bufIdx++;
                }
            }
            if (images != null) images.position(0);
            if (buffers != null) buffers.position(0);
            writes.position(0);
            VK10.vkUpdateDescriptorSets(MojangVulkanBridge.vkDevice(), writes, null);
        }
    }
}
