package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * A task+mesh+fragment graphics {@code VkPipeline} built with dynamic rendering
 * ({@code VK_KHR_dynamic_rendering} — core in 1.3, available on 26.2's Vulkan 1.2+). Rasterizer
 * state is intentionally simple — vulkium doesn't need vertex-input state (mesh shaders emit
 * their own vertices), and render-pass objects are unnecessary under dynamic rendering.
 *
 * <p>Viewport + scissor are set as dynamic state so the pipeline survives swapchain resizes.
 */
public final class MeshPipeline implements AutoCloseable {
    private final long handle;
    private final PipelineLayout layout;
    private boolean closed;

    private MeshPipeline(long handle, PipelineLayout layout) {
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

    public static Builder builder(PipelineLayout layout) { return new Builder(layout); }

    public static final class Builder {
        private final PipelineLayout layout;
        private ShaderModule task;
        private ShaderModule mesh;
        private ShaderModule fragment;
        private int colorFormat = VK10.VK_FORMAT_R8G8B8A8_UNORM;
        private int depthFormat = VK10.VK_FORMAT_D32_SFLOAT;
        private boolean depthTest = true;
        private boolean depthWrite = true;
        private boolean blend = false;
        private int cullMode = VK10.VK_CULL_MODE_BACK_BIT;

        Builder(PipelineLayout layout) { this.layout = layout; }

        public Builder task(ShaderModule m) { this.task = m; return this; }
        public Builder mesh(ShaderModule m) { this.mesh = m; return this; }
        public Builder fragment(ShaderModule m) { this.fragment = m; return this; }
        public Builder colorFormat(int vkFormat) { this.colorFormat = vkFormat; return this; }
        public Builder depthFormat(int vkFormat) { this.depthFormat = vkFormat; return this; }
        public Builder depthTest(boolean enable) { this.depthTest = enable; return this; }
        public Builder depthWrite(boolean enable) { this.depthWrite = enable; return this; }
        public Builder blend(boolean enable) { this.blend = enable; return this; }
        public Builder cullMode(int vkCullMode) { this.cullMode = vkCullMode; return this; }

        public MeshPipeline build() {
            if (mesh == null || fragment == null) {
                throw new IllegalStateException("mesh + fragment shaders are required");
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                int stageCount = 2 + (task != null ? 1 : 0);
                VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);

                int idx = 0;
                if (task != null) {
                    stages.position(idx++)
                        .sType$Default()
                        .stage(0x00000040 /* VK_SHADER_STAGE_TASK_BIT_EXT */)
                        .module(task.handle())
                        .pName(stack.UTF8(task.entryPoint()));
                }
                stages.position(idx++)
                    .sType$Default()
                    .stage(0x00000080 /* VK_SHADER_STAGE_MESH_BIT_EXT */)
                    .module(mesh.handle())
                    .pName(stack.UTF8(mesh.entryPoint()));
                stages.position(idx++)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragment.handle())
                    .pName(stack.UTF8(fragment.entryPoint()));
                stages.position(0);

                // Mesh pipelines omit VkPipelineVertexInputStateCreateInfo and
                // VkPipelineInputAssemblyStateCreateInfo — vertex fetch is the mesh shader's job.

                VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

                VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(cullMode)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);

                VkPipelineMultisampleStateCreateInfo msaa = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

                VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(depthTest)
                    .depthWriteEnable(depthWrite)
                    .depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

                VkPipelineColorBlendAttachmentState.Buffer attach = VkPipelineColorBlendAttachmentState.calloc(1, stack);
                if (blend) {
                    attach.position(0)
                        .blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
                }
                attach.position(0).colorWriteMask(0xF /* RGBA */);

                VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(attach);

                IntBuffer dynamicStates = stack.ints(
                    VK10.VK_DYNAMIC_STATE_VIEWPORT,
                    VK10.VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(dynamicStates);

                VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType$Default()
                    .pColorAttachmentFormats(stack.ints(colorFormat))
                    .depthAttachmentFormat(depthFormat);

                VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pNext(rendering.address())
                    .pStages(stages)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(msaa)
                    .pDepthStencilState(depth)
                    .pColorBlendState(blendState)
                    .pDynamicState(dynamic)
                    .layout(layout.handle());

                LongBuffer pPipeline = stack.callocLong(1);
                int result = VK10.vkCreateGraphicsPipelines(
                    MojangVulkanBridge.vkDevice(), MemoryUtil.NULL, info, null, pPipeline);
                if (result != VK10.VK_SUCCESS) {
                    throw new RuntimeException("vkCreateGraphicsPipelines (mesh) failed: VkResult=" + result);
                }
                return new MeshPipeline(pPipeline.get(0), layout);
            }
        }
    }
}
