package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A single {@code VkDescriptorSetLayout}. Always built with
 * {@code VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR} — vulkium uses push
 * descriptors exclusively, so no descriptor pool / allocated set exists.
 */
public final class DescriptorSetLayout implements AutoCloseable {
    private final long handle;
    private boolean closed;

    private DescriptorSetLayout(long handle) {
        this.handle = handle;
    }

    public long handle() { return handle; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyDescriptorSetLayout(MojangVulkanBridge.vkDevice(), handle, null);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Binding> bindings = new ArrayList<>();
        private boolean pushDescriptor = true;

        public Builder binding(int binding, int vkDescriptorType, int count, int stageFlags) {
            bindings.add(new Binding(binding, vkDescriptorType, count, stageFlags));
            return this;
        }

        public Builder pushDescriptor(boolean enable) {
            this.pushDescriptor = enable;
            return this;
        }

        public DescriptorSetLayout build() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer buf = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
                for (int i = 0; i < bindings.size(); i++) {
                    Binding b = bindings.get(i);
                    buf.position(i)
                        .binding(b.binding)
                        .descriptorType(b.descriptorType)
                        .descriptorCount(b.count)
                        .stageFlags(b.stageFlags);
                }
                buf.position(0);

                int flags = pushDescriptor ? 0x00000001 /* VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR */ : 0;
                VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(flags)
                    .pBindings(buf);

                LongBuffer pLayout = stack.callocLong(1);
                int result = VK10.vkCreateDescriptorSetLayout(MojangVulkanBridge.vkDevice(), info, null, pLayout);
                if (result != VK10.VK_SUCCESS) {
                    throw new RuntimeException("vkCreateDescriptorSetLayout failed: VkResult=" + result);
                }
                return new DescriptorSetLayout(pLayout.get(0));
            }
        }
    }

    private record Binding(int binding, int descriptorType, int count, int stageFlags) {}
}
