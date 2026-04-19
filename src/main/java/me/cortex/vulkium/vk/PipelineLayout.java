package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.nio.LongBuffer;

/**
 * A {@code VkPipelineLayout} composed of up to a handful of descriptor set layouts plus one
 * push-constant range. Vulkium's layouts are very small — typically one set of push descriptors
 * for textures and a push-constant block for the scene-data pointers (buffer-reference addresses
 * + per-draw scalars).
 */
public final class PipelineLayout implements AutoCloseable {
    private final long handle;
    private final int pushConstantStages;
    private final int pushConstantSize;
    private boolean closed;

    private PipelineLayout(long handle, int pushConstantStages, int pushConstantSize) {
        this.handle = handle;
        this.pushConstantStages = pushConstantStages;
        this.pushConstantSize = pushConstantSize;
    }

    public long handle() { return handle; }
    public int pushConstantStages() { return pushConstantStages; }
    public int pushConstantSize() { return pushConstantSize; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyPipelineLayout(MojangVulkanBridge.vkDevice(), handle, null);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long[] setLayouts = new long[0];
        private int pushConstantStages = 0;
        private int pushConstantSize = 0;

        public Builder setLayouts(DescriptorSetLayout... layouts) {
            this.setLayouts = new long[layouts.length];
            for (int i = 0; i < layouts.length; i++) this.setLayouts[i] = layouts[i].handle();
            return this;
        }

        /** Push-constant range at offset 0 with the given byte size, visible in the given VK stage flags. */
        public Builder pushConstants(int stageFlags, int size) {
            this.pushConstantStages = stageFlags;
            this.pushConstantSize = size;
            return this;
        }

        public PipelineLayout build() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default();

                if (setLayouts.length > 0) {
                    LongBuffer setBuf = stack.callocLong(setLayouts.length);
                    for (long l : setLayouts) setBuf.put(l);
                    setBuf.flip();
                    info.pSetLayouts(setBuf);
                }

                if (pushConstantSize > 0) {
                    VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(pushConstantStages)
                        .offset(0)
                        .size(pushConstantSize);
                    info.pPushConstantRanges(range);
                }

                LongBuffer pLayout = stack.callocLong(1);
                int result = VK10.vkCreatePipelineLayout(MojangVulkanBridge.vkDevice(), info, null, pLayout);
                if (result != VK10.VK_SUCCESS) {
                    throw new RuntimeException("vkCreatePipelineLayout failed: VkResult=" + result);
                }
                return new PipelineLayout(pLayout.get(0), pushConstantStages, pushConstantSize);
            }
        }
    }
}
