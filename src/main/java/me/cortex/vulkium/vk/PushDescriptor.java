package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Tiny helper for {@code vkCmdPushDescriptorSetKHR}. Vulkium doesn't allocate descriptor pools;
 * every binding is pushed per-draw via this API, so the layout needs
 * {@code VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR} (set by default on
 * {@link DescriptorSetLayout}).
 *
 * <p>Typical usage inside a command-buffer recording lambda:
 * <pre>{@code
 *   PushDescriptor.builder(pipelineLayout.handle(), VK_PIPELINE_BIND_POINT_COMPUTE, 0)
 *     .uniformBuffer(0, sceneUniform.buffer().handle(), 0, SceneUniform.SCENE_UBO_SIZE)
 *     .push(cmd);
 * }</pre>
 *
 * <p>Resolves {@code vkCmdPushDescriptorSetKHR} lazily at first use — the extension is part of
 * vulkium's feature gate so it's always present when vulkium is enabled.
 */
public final class PushDescriptor {

    private final long pipelineLayout;
    private final int bindPoint;
    private final int set;
    private final java.util.List<Write> writes = new java.util.ArrayList<>();

    private PushDescriptor(long pipelineLayout, int bindPoint, int set) {
        this.pipelineLayout = pipelineLayout;
        this.bindPoint = bindPoint;
        this.set = set;
    }

    public static PushDescriptor builder(long pipelineLayout, int bindPoint, int set) {
        return new PushDescriptor(pipelineLayout, bindPoint, set);
    }

    public PushDescriptor uniformBuffer(int binding, long bufferHandle, long offset, long range) {
        writes.add(new Write(binding, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, bufferHandle, offset, range, 0L, 0L, 0));
        return this;
    }

    public PushDescriptor storageBuffer(int binding, long bufferHandle, long offset, long range) {
        writes.add(new Write(binding, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, bufferHandle, offset, range, 0L, 0L, 0));
        return this;
    }

    public PushDescriptor combinedImageSampler(int binding, long imageView, long sampler, int imageLayout) {
        writes.add(new Write(binding, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0L, 0L, 0L, imageView, sampler, imageLayout));
        return this;
    }

    public void push(VkCommandBuffer cmd) {
        if (writes.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writeBuf = VkWriteDescriptorSet.calloc(writes.size(), stack);

            for (int i = 0; i < writes.size(); i++) {
                Write w = writes.get(i);
                writeBuf.position(i)
                    .sType$Default()
                    .dstSet(0L)
                    .dstBinding(w.binding)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(w.type);

                switch (w.type) {
                    case VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
                    case VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER: {
                        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(w.bufferHandle).offset(w.offset).range(w.range);
                        writeBuf.pBufferInfo(info);
                        break;
                    }
                    case VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER: {
                        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(w.imageView).sampler(w.sampler).imageLayout(w.imageLayout);
                        writeBuf.pImageInfo(info);
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unsupported descriptor type: " + w.type);
                }
            }
            writeBuf.position(0);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, bindPoint, pipelineLayout, set, writeBuf);
        }
    }

    private record Write(int binding, int type,
                         long bufferHandle, long offset, long range,
                         long imageView, long sampler, int imageLayout) {}
}
