package me.cortex.vulkium.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/**
 * Records a one-shot primary command buffer via Mojang's
 * {@link VulkanCommandEncoder#allocateAndBeginTransientCommandBuffer()} (PRIMARY, already begun
 * with ONE_TIME_SUBMIT by Mojang as of 26.2-pre-2), runs the caller's recording code, and hands
 * it to Mojang's submission via {@link VulkanCommandEncoder#execute(VkCommandBuffer)}.
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

        // Mojang's allocateAndBeginTransientCommandBuffer now allocates a PRIMARY buffer and
        // calls vkBeginCommandBuffer with ONE_TIME_SUBMIT internally — same semantics as the
        // old allocateTransientCommandBuffer(true) + our own vkBeginCommandBuffer. Don't begin
        // again here (Vulkan: vkBeginCommandBuffer on an already-recording buffer is invalid).
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
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
        // NOTE: do NOT call encoder.submit() here. That would force a mid-frame
        // vkQueueSubmit which separates our work from Mojang's, causing ordering hazards
        // (their subsequent writes end up in a later submission that overwrites ours).
        // For callers that need the work to complete synchronously (startup smoke tests),
        // use {@link #recordAndFlushNow(Consumer)} instead.
    }

    /**
     * Same as {@link #recordAndSubmit(Consumer)} but forces an immediate
     * {@code vkQueueSubmit} via {@code encoder.submit()}. Use only for work that has to
     * complete before we return (boot-time smoke tests, synchronous readbacks). Do NOT
     * use for per-frame dispatches — it fractures Mojang's submission batch.
     */
    public static void recordAndFlushNow(Consumer<VkCommandBuffer> recorder) {
        VulkanCommandEncoder encoder = MojangVulkanBridge.commandEncoder();
        if (encoder == null) {
            throw new IllegalStateException("Mojang VulkanCommandEncoder not available — Vulkan backend not active?");
        }
        recordAndSubmit(recorder);
        encoder.submit();
    }
}
