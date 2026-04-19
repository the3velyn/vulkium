package me.cortex.vulkium.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;

import java.util.function.Consumer;

/**
 * Records a one-shot primary command buffer via Mojang's
 * {@link VulkanCommandEncoder#allocateTransientCommandBuffer(boolean)} (PRIMARY), runs the
 * caller's recording code, and hands it to Mojang's submission via
 * {@link VulkanCommandEncoder#execute(VkCommandBuffer)}.
 *
 * <p>Mojang's {@code execute()} ends their current in-flight primary first, then queues our
 * command buffer in the same frame submission — ordering with their draws is preserved.
 *
 * <p>For passes that need to issue draws into Mojang's color/depth attachments, a
 * secondary-command-buffer path with inherited rendering state will land alongside; this is
 * the compute / transfer / between-passes variant.
 */
public final class CommandRecorder {
    private CommandRecorder() {}

    /**
     * Allocate a primary command buffer, begin it with ONE_TIME_SUBMIT, run {@code recorder},
     * end it, and submit it. Throws if any VK call fails — do not use this path unless you've
     * verified Mojang's VK backend is live.
     */
    public static void recordAndSubmit(Consumer<VkCommandBuffer> recorder) {
        VulkanCommandEncoder encoder = MojangVulkanBridge.commandEncoder();
        if (encoder == null) {
            throw new IllegalStateException("Mojang VulkanCommandEncoder not available — Vulkan backend not active?");
        }

        VkCommandBuffer cmd = encoder.allocateTransientCommandBuffer(true /* PRIMARY */);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            int br = VK10.vkBeginCommandBuffer(cmd, begin);
            if (br != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkBeginCommandBuffer failed: VkResult=" + br);
            }
        }

        try {
            recorder.accept(cmd);
        } catch (RuntimeException e) {
            // Best-effort end so the pool isn't left with a dangling open cmd buffer.
            try { VK10.vkEndCommandBuffer(cmd); } catch (Throwable ignored) { /* swallow — original error matters */ }
            throw e;
        }

        int er = VK10.vkEndCommandBuffer(cmd);
        if (er != VK10.VK_SUCCESS) {
            throw new RuntimeException("vkEndCommandBuffer failed: VkResult=" + er);
        }

        encoder.execute(cmd);
    }
}
