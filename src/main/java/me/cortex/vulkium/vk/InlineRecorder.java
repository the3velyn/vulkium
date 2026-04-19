package me.cortex.vulkium.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/**
 * Record commands directly into Mojang's currently-open primary command buffer — no new
 * cmd-buffer allocation, no separate submit. Our commands get spliced inline at the exact
 * frame point where the caller invokes us, executing in natural ordering with Mojang's own
 * recording.
 *
 * <p>Use this when {@link CommandRecorder} would cause an ordering hazard — specifically when
 * Mojang's subsequent frame work (final composite, HUD, post-fx) would overwrite our output
 * if we submitted on a separate boundary.
 *
 * <p><b>Constraint:</b> Mojang's primary must not currently have a dynamic-rendering scope
 * open (otherwise {@code vkCmdClearColorImage} and layout barriers outside a render pass are
 * undefined behavior). Recording {@code vkCmdBeginRendering}/{@code vkCmdEndRendering} pairs
 * within our lambda is legal.
 */
public final class InlineRecorder {
    private InlineRecorder() {}

    /**
     * Run {@code recorder} with Mojang's current primary {@code VkCommandBuffer}. Returns
     * {@code false} if no primary is currently open (caller should skip).
     */
    public static boolean recordInPrimary(Consumer<VkCommandBuffer> recorder) {
        VulkanCommandEncoder encoder = MojangVulkanBridge.commandEncoder();
        if (encoder == null) return false;
        VkCommandBuffer primary = encoder.currentCommandBuffer;
        if (primary == null) return false;
        recorder.accept(primary);
        return true;
    }
}
