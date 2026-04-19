package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;

import java.nio.LongBuffer;

/**
 * A compute {@code VkPipeline} — the simplest pipeline kind, used for vulkium's region-sort
 * compute shader and (future V8) HZB generation.
 */
public final class ComputePipeline implements AutoCloseable {
    private final long handle;
    private final PipelineLayout layout;
    private boolean closed;

    private ComputePipeline(long handle, PipelineLayout layout) {
        this.handle = handle;
        this.layout = layout;
    }

    public long handle() { return handle; }
    public PipelineLayout layout() { return layout; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyPipeline(MojangVulkanBridge.vkDevice(), handle, null);
    }

    public static ComputePipeline create(ShaderModule compute, PipelineLayout layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .module(compute.handle())
                .pName(stack.UTF8(compute.entryPoint()));

            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType$Default()
                .stage(stage)
                .layout(layout.handle());

            LongBuffer pPipeline = stack.callocLong(1);
            int result = VK10.vkCreateComputePipelines(
                MojangVulkanBridge.vkDevice(), MemoryUtil.NULL, info, null, pPipeline);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines failed: VkResult=" + result);
            }
            return new ComputePipeline(pPipeline.get(0), layout);
        }
    }
}
