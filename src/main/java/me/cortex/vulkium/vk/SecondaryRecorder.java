package me.cortex.vulkium.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceRenderingInfo;

import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * Records a one-shot SECONDARY command buffer that inherits Mojang's dynamic-rendering state,
 * then hands it to Mojang's submission via {@link VulkanCommandEncoder#execute(VkCommandBuffer)}.
 *
 * <p>This is the graphics-draw sibling of {@link CommandRecorder}. Where {@code CommandRecorder}
 * records a PRIMARY buffer (good for compute / transfers between passes), this records a
 * SECONDARY buffer that Mojang will splice inside its current {@code vkCmdBeginRendering} /
 * {@code vkCmdEndRendering} scope via {@code vkCmdExecuteCommands}. Mojang's backend enables
 * dynamic rendering (VK 1.3 core, or VK_KHR_dynamic_rendering on 1.2), so we pass a
 * {@link VkCommandBufferInheritanceRenderingInfo} via the inheritance-info pNext chain rather
 * than a {@code renderPass} / {@code subpass} / {@code framebuffer} triple.
 *
 * <p><b>Callers must not</b> emit {@code vkCmdBeginRendering}/{@code vkCmdEndRendering} inside
 * the recorder lambda — the secondary runs <em>inside</em> Mojang's already-active render scope.
 * Only draw calls, pipeline binds, descriptor pushes, and dynamic-state updates are legal.
 *
 * <p>The attachment formats in the {@link InheritanceSpec} must match — slot-for-slot — the
 * formats of the primary's current render. A mismatch is undefined behavior per the Vulkan spec
 * and will typically trigger a validation error or a driver crash.
 */
public final class SecondaryRecorder {
    private SecondaryRecorder() {}

    /**
     * Describes the dynamic-rendering state the secondary inherits from the primary.
     *
     * @param colorAttachmentFormats one {@code VkFormat} per color attachment the primary has
     *     bound, in slot order. Use {@code VK_FORMAT_UNDEFINED} for unused slots. May be empty
     *     for depth-only passes.
     * @param depthAttachmentFormat {@code VkFormat} of the depth attachment, or
     *     {@code VK_FORMAT_UNDEFINED} if no depth attachment is bound on the primary.
     * @param stencilAttachmentFormat {@code VkFormat} of the stencil attachment, or
     *     {@code VK_FORMAT_UNDEFINED} if no stencil attachment is bound on the primary.
     * @param rasterizationSamples MSAA sample count (typ. {@code VK_SAMPLE_COUNT_1_BIT}); must
     *     match the sample count of the primary's attachments.
     */
    public record InheritanceSpec(
        int[] colorAttachmentFormats,
        int depthAttachmentFormat,
        int stencilAttachmentFormat,
        int rasterizationSamples
    ) {}

    /**
     * Allocate a SECONDARY command buffer, begin it with ONE_TIME_SUBMIT | RENDER_PASS_CONTINUE
     * and a dynamic-rendering inheritance chain, run {@code recorder}, end it, and hand it to
     * Mojang's encoder. Mojang will emit {@code vkCmdExecuteCommands} on its current primary,
     * so the recorded draws land inside its active render scope.
     *
     * @throws IllegalStateException if the Vulkan backend is not live.
     * @throws RuntimeException if any Vulkan call returns a non-success result.
     */
    public static void recordAndSubmit(InheritanceSpec spec, Consumer<VkCommandBuffer> recorder) {
        if (spec == null) throw new NullPointerException("spec");
        if (spec.colorAttachmentFormats == null) {
            throw new NullPointerException("spec.colorAttachmentFormats (use an empty array for no color attachments)");
        }

        VulkanCommandEncoder encoder = MojangVulkanBridge.commandEncoder();
        if (encoder == null) {
            throw new IllegalStateException("Mojang VulkanCommandEncoder not available — Vulkan backend not active?");
        }

        VkCommandBuffer cmd = encoder.allocateTransientCommandBuffer(false /* SECONDARY */);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pColorFormats = null;
            if (spec.colorAttachmentFormats.length > 0) {
                pColorFormats = stack.mallocInt(spec.colorAttachmentFormats.length);
                pColorFormats.put(spec.colorAttachmentFormats).flip();
            }

            // VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_RENDERING_INFO (VK 1.3 core)
            VkCommandBufferInheritanceRenderingInfo renderingInherit = VkCommandBufferInheritanceRenderingInfo.calloc(stack)
                .sType$Default()
                .pNext(0L)
                .flags(0)
                .viewMask(0)
                .pColorAttachmentFormats(pColorFormats)
                .depthAttachmentFormat(spec.depthAttachmentFormat)
                .stencilAttachmentFormat(spec.stencilAttachmentFormat)
                .rasterizationSamples(spec.rasterizationSamples);

            // VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO.
            // Render-pass-less (dynamic rendering): renderPass/framebuffer = VK_NULL_HANDLE,
            // subpass = 0. The actual state description hangs off pNext.
            VkCommandBufferInheritanceInfo inherit = VkCommandBufferInheritanceInfo.calloc(stack)
                .sType$Default()
                .pNext(renderingInherit)
                .renderPass(VK10.VK_NULL_HANDLE)
                .subpass(0)
                .framebuffer(VK10.VK_NULL_HANDLE)
                .occlusionQueryEnable(false)
                .queryFlags(0)
                .pipelineStatistics(0);

            // VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO.
            // RENDER_PASS_CONTINUE_BIT is mandatory for a secondary that executes inside a
            // render scope — including a dynamic-rendering scope.
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                     | VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
                .pInheritanceInfo(inherit);

            int br = VK10.vkBeginCommandBuffer(cmd, begin);
            if (br != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkBeginCommandBuffer (SECONDARY) failed: VkResult=" + br);
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
            throw new RuntimeException("vkEndCommandBuffer (SECONDARY) failed: VkResult=" + er);
        }

        // Mojang's execute(VkCommandBuffer) detects SECONDARY vs PRIMARY and emits either
        // vkCmdExecuteCommands on the current primary (SECONDARY) or a submission-chain step
        // (PRIMARY). We want the SECONDARY path — draws will land inside the active
        // vkCmdBeginRendering/vkCmdEndRendering scope that Mojang opened.
        encoder.execute(cmd);
    }
}
