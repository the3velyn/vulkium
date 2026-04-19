package me.cortex.vulkium.vk;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * High-level wrapper around a compute-shader dispatch: owns the {@link ShaderModule},
 * optional set-0 push-descriptor {@link DescriptorSetLayout}, {@link PipelineLayout} with an
 * optional push-constant range, and {@link ComputePipeline}. The {@link #record} /
 * {@link #dispatchOnce} helpers take care of binding, pushing constants, populating the
 * push-descriptor set via a caller-supplied lambda, and issuing {@code vkCmdDispatch}.
 *
 * <p>Vulkium pushes descriptors per-dispatch, so no descriptor pool is involved — the
 * underlying {@link DescriptorSetLayout.Builder} defaults to push-descriptor. If no
 * descriptor bindings are provided the pipeline layout is built without any set layouts.
 *
 * <p>Close in reverse construction order: pipeline → pipeline layout → descriptor set layout
 * → shader module. This is safe because the compute pipeline references the pipeline layout
 * and shader module, and the pipeline layout references the descriptor set layout.
 */
public final class ComputeDispatch implements AutoCloseable {

    /** Binding spec mirrors {@link DescriptorSetLayout.Builder#binding(int, int, int, int)}. */
    public record Binding(int binding, int vkDescriptorType, int count) {}

    private final ShaderModule shader;
    private final DescriptorSetLayout setLayout; // nullable
    private final PipelineLayout pipelineLayout;
    private final ComputePipeline pipeline;
    private final int pushConstantSize;
    private boolean closed;

    private ComputeDispatch(ShaderModule shader,
                            DescriptorSetLayout setLayout,
                            PipelineLayout pipelineLayout,
                            ComputePipeline pipeline,
                            int pushConstantSize) {
        this.shader = shader;
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.pushConstantSize = pushConstantSize;
    }

    /**
     * Create a compute dispatch. {@code shaderResource} is a path under
     * {@code assets/vulkium/shaders/} (e.g. {@code "sorting/region_section_sorter.comp"}).
     * {@code pushConstantSize} is bytes (may be 0). {@code descriptorBindings} (may be
     * empty) describes the single set-0 push-descriptor layout.
     */
    public static ComputeDispatch create(String shaderResource,
                                         Map<String, String> defines,
                                         int pushConstantSize,
                                         List<Binding> descriptorBindings) {
        ShaderModule shader = null;
        DescriptorSetLayout setLayout = null;
        PipelineLayout pipelineLayout = null;
        ComputePipeline pipeline = null;
        try {
            shader = ShaderModule.compileFromResource(shaderResource, ShaderStage.COMPUTE, defines);

            PipelineLayout.Builder layoutBuilder = PipelineLayout.builder();
            if (descriptorBindings != null && !descriptorBindings.isEmpty()) {
                DescriptorSetLayout.Builder setBuilder = DescriptorSetLayout.builder();
                for (Binding b : descriptorBindings) {
                    setBuilder.binding(b.binding(), b.vkDescriptorType(), b.count(),
                        VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                }
                setLayout = setBuilder.build();
                layoutBuilder.setLayouts(setLayout);
            }
            if (pushConstantSize > 0) {
                layoutBuilder.pushConstants(VK10.VK_SHADER_STAGE_COMPUTE_BIT, pushConstantSize);
            }
            pipelineLayout = layoutBuilder.build();
            pipeline = ComputePipeline.create(shader, pipelineLayout);

            ComputeDispatch dispatch = new ComputeDispatch(shader, setLayout, pipelineLayout,
                pipeline, pushConstantSize);
            // Ownership transferred — null locals so the catch block doesn't double-free.
            shader = null;
            setLayout = null;
            pipelineLayout = null;
            pipeline = null;
            return dispatch;
        } finally {
            // Unwind partial construction on failure, in reverse order.
            if (pipeline != null) pipeline.close();
            if (pipelineLayout != null) pipelineLayout.close();
            if (setLayout != null) setLayout.close();
            if (shader != null) shader.close();
        }
    }

    /**
     * Record a dispatch into the provided command buffer. Binds the pipeline, pushes the
     * given constants (may be {@code null} iff this dispatch was created with
     * {@code pushConstantSize == 0}), lets the caller populate the descriptor via the
     * supplied {@link PushDescriptor} builder, then issues {@code vkCmdDispatch(gx,gy,gz)}.
     */
    public void record(VkCommandBuffer cmd,
                       ByteBuffer pushConstants,
                       Consumer<PushDescriptor> bindings,
                       int groupsX, int groupsY, int groupsZ) {
        if (closed) throw new IllegalStateException("ComputeDispatch is closed");

        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());

        if (pushConstantSize > 0) {
            if (pushConstants == null) {
                throw new IllegalArgumentException(
                    "pushConstants must be non-null when pushConstantSize=" + pushConstantSize);
            }
            VK10.vkCmdPushConstants(cmd, pipelineLayout.handle(),
                VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
        }

        if (bindings != null && setLayout != null) {
            PushDescriptor pd = PushDescriptor.builder(
                pipelineLayout.handle(), VK10.VK_PIPELINE_BIND_POINT_COMPUTE, 0);
            bindings.accept(pd);
            pd.push(cmd);
        }

        VK10.vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
    }

    /** Convenience: allocate a primary cmd buffer via {@link CommandRecorder}, record, submit. */
    public void dispatchOnce(ByteBuffer pushConstants,
                             Consumer<PushDescriptor> bindings,
                             int groupsX, int groupsY, int groupsZ) {
        CommandRecorder.recordAndSubmit(cmd ->
            record(cmd, pushConstants, bindings, groupsX, groupsY, groupsZ));
    }

    public PipelineLayout layout() { return pipelineLayout; }
    public ComputePipeline pipeline() { return pipeline; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Reverse of construction: pipeline depends on pipelineLayout + shader;
        // pipelineLayout depends on setLayout. Destroy dependents first.
        pipeline.close();
        pipelineLayout.close();
        if (setLayout != null) setLayout.close();
        shader.close();
    }
}
