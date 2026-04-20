package me.cortex.vulkium.render;

import me.cortex.vulkium.vk.DescriptorSetLayout;
import me.cortex.vulkium.vk.MeshPipeline;
import me.cortex.vulkium.vk.PipelineLayout;
import me.cortex.vulkium.vk.PushDescriptor;
import me.cortex.vulkium.vk.ShaderModule;
import me.cortex.vulkium.vk.ShaderStage;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Map;

/**
 * The first mesh-shader graphics pass in vulkium: the primary terrain rasterizer. Compiles and
 * caches the task+mesh+fragment pipelines (fog-off and fog-on variants) and records a single
 * {@code vkCmdDrawMeshTasksEXT} call per invocation to drive MC 26.2 terrain rendering through
 * {@code VK_EXT_mesh_shader}.
 *
 * <p>Descriptor sets:
 * <ul>
 *   <li><b>set 0</b>, binding 0 — the {@link SceneUniform} scene UBO. Visible to task, mesh, and
 *       fragment stages. Pushed per-{@link #record} via {@code vkCmdPushDescriptorSetKHR}.</li>
 *   <li><b>set 1</b>, bindings 0 + 1 — combined-image-samplers for {@code tex_diffuse} and
 *       {@code tex_light} at the fragment stage. Declared here so the pipeline layout matches
 *       the fragment shader's expectations, but {@link #record} does <b>not</b> populate them —
 *       callers must push their own texture descriptors via {@link #textureSetLayout} + a
 *       {@link PushDescriptor} before invoking {@code record}.</li>
 * </ul>
 *
 * <p>Uses dynamic rendering ({@code VK_KHR_dynamic_rendering}, core in 1.3). The caller supplies
 * a SECONDARY command buffer whose inheritance info matches {@link #COLOR_FORMAT} and
 * {@link #DEPTH_FORMAT}; see {@link me.cortex.vulkium.vk.SecondaryRecorder}.
 */
public final class PrimaryTerrainPass implements AutoCloseable {

    /** Color attachment format the pipelines are built against. Matches Mojang's swapchain. */
    public static final int COLOR_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    /** Depth attachment format the pipelines are built against. MC 26.2's mainRenderTarget
     *  uses VK_FORMAT_D32_SFLOAT (126, no stencil); mismatching the pipeline format silently
     *  fails to draw. */
    public static final int DEPTH_FORMAT = VK10.VK_FORMAT_D32_SFLOAT;

    // VK_SHADER_STAGE_TASK_BIT_EXT | VK_SHADER_STAGE_MESH_BIT_EXT | VK_SHADER_STAGE_FRAGMENT_BIT.
    // The scene UBO is read by all three stages of the pipeline (task shader reads chunk+section
    // pointers; mesh shader reads MVP + vertex buffers; fragment shader reads fog/ptr uniforms).
    private static final int SCENE_UBO_STAGES =
            0x00000040 /* VK_SHADER_STAGE_TASK_BIT_EXT */
          | 0x00000080 /* VK_SHADER_STAGE_MESH_BIT_EXT */
          | VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

    // Shader modules — retained so close() can destroy them. Each is compiled once and shared
    // between the fog-off and fog-on pipeline variants (mesh.glsl + frag.frag are recompiled
    // with the RENDER_FOG define for the fog-on variant since the vertex/fragment interpolants
    // differ).
    private final ShaderModule taskModule;
    private final ShaderModule meshModuleNoFog;
    private final ShaderModule meshModuleFog;
    private final ShaderModule fragModuleNoFog;
    private final ShaderModule fragModuleFog;

    private final DescriptorSetLayout sceneUboSetLayout;
    private final DescriptorSetLayout textureSetLayout;
    private final PipelineLayout pipelineLayout;

    private final MeshPipeline pipelineNoFog;
    private final MeshPipeline pipelineFog;
    /** Translucent variant: blend on, depth write off, task shader uses the high-16 of
     *  renderRanges.w (translucent quad count) and offset = header.w + opaque count. */
    private final MeshPipeline pipelineTranslucent;
    private final ShaderModule taskTranslucentModule;
    private final ShaderModule meshTranslucentModule;

    // Unified descriptor pool + sets for BOTH scene UBO (set=0) and textures (set=1). Push
    // descriptor interacted badly (set=0 push silently invalidated set=1 binding → textureSize
    // returned 0). Both sets allocated from this pool; scene UBO binding updated at init from
    // the StagingBuffer backing it (fixed buffer handle, mutable data per-frame).
    private final me.cortex.vulkium.vk.DescriptorPool descriptorPool;
    private final long sceneDescriptorSet;
    private final long textureDescriptorSet;
    private long sceneDescriptorBoundBuffer = 0L;
    private long lastBoundAtlasView = 0L;
    private long lastBoundAtlasSampler = 0L;

    private boolean closed;

    /**
     * Eagerly compiles all three shader stages (once plain, once with {@code RENDER_FOG=1}),
     * builds the pipeline layout, and creates the two mesh graphics pipelines. Any shaderc
     * compilation failure or {@code vkCreate*} error aborts construction and propagates to the
     * caller; partial resources are cleaned up before the throw.
     */
    public PrimaryTerrainPass() {
        // Shader compile phase. Allocate modules first; if anything downstream fails we destroy
        // them in the catch block so we never leak a VkShaderModule.
        ShaderModule task = null;
        ShaderModule meshNo = null;
        ShaderModule meshFog = null;
        ShaderModule fragNo = null;
        ShaderModule fragFog = null;
        DescriptorSetLayout sceneLayout = null;
        DescriptorSetLayout texLayout = null;
        PipelineLayout pLayout = null;
        MeshPipeline pipeNo = null;
        MeshPipeline pipeFog = null;
        MeshPipeline pipeTrans = null;
        ShaderModule taskTrans = null;
        ShaderModule meshTrans = null;
        try {
            Map<String, String> noDefines = Map.of();
            Map<String, String> fogDefines = Map.of("RENDER_FOG", "1");

            // Task shader has no fog-specific code path, so one module covers both variants.
            task = ShaderModule.compileFromResource("terrain/task.glsl", ShaderStage.TASK, noDefines);
            meshNo = ShaderModule.compileFromResource("terrain/mesh.glsl", ShaderStage.MESH, noDefines);
            meshFog = ShaderModule.compileFromResource("terrain/mesh.glsl", ShaderStage.MESH, fogDefines);
            fragNo = ShaderModule.compileFromResource("terrain/frag.frag", ShaderStage.FRAGMENT, noDefines);
            fragFog = ShaderModule.compileFromResource("terrain/frag.frag", ShaderStage.FRAGMENT, fogDefines);

            // Single combined descriptor set (set=0): scene UBO at binding=0 + atlas sampler
            // at binding=1 + lightmap sampler at binding=2. Vulkan allows only ONE set per
            // pipeline layout with PUSH_DESCRIPTOR; combining everything into one set means we
            // can push everything as a coherent unit, avoiding cross-set invalidation.
            sceneLayout = DescriptorSetLayout.builder()
                    .binding(0, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, SCENE_UBO_STAGES)
                    .binding(1, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                            VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .binding(2, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                            VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .pushDescriptor(true)
                    .build();
            // Kept for API compatibility — points at the same layout now. Texture bindings
            // moved into sceneLayout so pipelineLayout has exactly one set.
            texLayout = sceneLayout;

            // Pipeline layout: single combined set, no push constants.
            pLayout = PipelineLayout.builder()
                    .setLayouts(sceneLayout)
                    .build();

            // Two pipelines, same layout. The only differences are the mesh + fragment
            // variants — the task stage is identical.
            pipeNo = MeshPipeline.builder(pLayout)
                    .task(task)
                    .mesh(meshNo)
                    .fragment(fragNo)
                    .colorFormat(COLOR_FORMAT)
                    .depthFormat(DEPTH_FORMAT)
                    .depthTest(true)
                    .depthWrite(true)
                    .blend(false)
                    .cullMode(VK10.VK_CULL_MODE_FRONT_BIT)
                    .build();

            pipeFog = MeshPipeline.builder(pLayout)
                    .task(task)
                    .mesh(meshFog)
                    .fragment(fragFog)
                    .colorFormat(COLOR_FORMAT)
                    .depthFormat(DEPTH_FORMAT)
                    .depthTest(true)
                    .depthWrite(true)
                    .blend(false)
                    .cullMode(VK10.VK_CULL_MODE_FRONT_BIT)
                    .build();

            // Translucent variant: compile task+mesh with TRANSLUCENT_PASS=1. Task emits from
            // the high-16 of renderRanges.w. Mesh reads with baseOffset shifted past opaque
            // quads. Pipeline has blend on, depth write off (read-only against opaque depth).
            Map<String, String> transDefines = Map.of("TRANSLUCENT_PASS", "1");
            taskTrans = ShaderModule.compileFromResource("terrain/task.glsl", ShaderStage.TASK, transDefines);
            meshTrans = ShaderModule.compileFromResource("terrain/mesh.glsl", ShaderStage.MESH, transDefines);
            pipeTrans = MeshPipeline.builder(pLayout)
                    .task(taskTrans)
                    .mesh(meshTrans)
                    .fragment(fragNo)
                    .colorFormat(COLOR_FORMAT)
                    .depthFormat(DEPTH_FORMAT)
                    .depthTest(true)
                    .depthWrite(false)
                    .blend(true)
                    // No cull on translucent — glass/ice panes need both faces visible,
                    // and with depth-write off back-face culling can hide the closer face
                    // of a glass block that draws before the one behind it. Double-draw cost
                    // is small vs. the visual improvement for most overlapping translucents.
                    .cullMode(VK10.VK_CULL_MODE_NONE)
                    .build();
        } catch (RuntimeException e) {
            if (pipeTrans != null)   pipeTrans.close();
            if (pipeFog != null)     pipeFog.close();
            if (pipeNo != null)      pipeNo.close();
            if (pLayout != null)     pLayout.close();
            if (texLayout != null && texLayout != sceneLayout) texLayout.close();
            if (sceneLayout != null) sceneLayout.close();
            if (meshTrans != null)   meshTrans.close();
            if (taskTrans != null)   taskTrans.close();
            if (fragFog != null)     fragFog.close();
            if (fragNo != null)      fragNo.close();
            if (meshFog != null)     meshFog.close();
            if (meshNo != null)      meshNo.close();
            if (task != null)        task.close();
            throw e;
        }

        this.taskModule = task;
        this.meshModuleNoFog = meshNo;
        this.meshModuleFog = meshFog;
        this.fragModuleNoFog = fragNo;
        this.fragModuleFog = fragFog;
        this.sceneUboSetLayout = sceneLayout;
        this.textureSetLayout = texLayout;
        this.pipelineLayout = pLayout;
        this.pipelineNoFog = pipeNo;
        this.pipelineFog = pipeFog;
        this.pipelineTranslucent = pipeTrans;
        this.taskTranslucentModule = taskTrans;
        this.meshTranslucentModule = meshTrans;

        // Allocate a pool sized for 2 sets: 1 UBO (scene) + 2 combined-image-samplers (texture).
        this.descriptorPool = me.cortex.vulkium.vk.DescriptorPool.forTypes(
            2,
            new int[] { VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                        VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER },
            new int[] { 1, 2 });
        this.sceneDescriptorSet = descriptorPool.allocateSet(this.sceneUboSetLayout);
        this.textureDescriptorSet = descriptorPool.allocateSet(this.textureSetLayout);
    }

    /** Called once the scene UBO's backing buffer is known (per-frame data changes, buffer is stable). */
    public void bindSceneBuffer(long uboBuffer, long offset, long range) {
        if (uboBuffer == sceneDescriptorBoundBuffer) return;
        new me.cortex.vulkium.vk.DescriptorSetWriter(sceneDescriptorSet)
            .uniformBuffer(0, uboBuffer, offset, range)
            .update();
        sceneDescriptorBoundBuffer = uboBuffer;
    }

    /** Rebuild the texture descriptor set when the atlas view or sampler changes. Cheap no-op
     *  when handles are identical to last call. */
    private void updateTextureDescriptors(long atlasView, long atlasSampler) {
        if (atlasView == lastBoundAtlasView && atlasSampler == lastBoundAtlasSampler) return;
        new me.cortex.vulkium.vk.DescriptorSetWriter(textureDescriptorSet)
            .combinedImageSampler(0, atlasView, atlasSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .combinedImageSampler(1, atlasView, atlasSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .update();
        lastBoundAtlasView = atlasView;
        lastBoundAtlasSampler = atlasSampler;
        org.slf4j.LoggerFactory.getLogger("vulkium/descriptor").info(
            "Updated texture descriptor set: view=0x{} sampler=0x{} set=0x{} layout=0x{}",
            Long.toHexString(atlasView), Long.toHexString(atlasSampler),
            Long.toHexString(textureDescriptorSet), Long.toHexString(pipelineLayout.handle()));
    }

    /** The shared {@link PipelineLayout} both variants are built against. */
    public PipelineLayout pipelineLayout() {
        return pipelineLayout;
    }

    /**
     * Descriptor set layout for set 0 — the scene UBO at binding 0. Exposed for callers that
     * want to mirror this structure in their own push-descriptor code paths.
     */
    public DescriptorSetLayout sceneUboSetLayout() {
        return sceneUboSetLayout;
    }

    /**
     * Descriptor set layout for set 1 — the terrain atlas + light map samplers at bindings 0
     * and 1 respectively. {@link #record} does <b>not</b> populate this set; callers must
     * push {@code combinedImageSampler} writes against set 1 via a {@link PushDescriptor}
     * before invoking {@link #record}, or the fragment shader's texture reads will hit
     * undefined descriptors.
     */
    public DescriptorSetLayout textureSetLayout() {
        return textureSetLayout;
    }

    /**
     * Record the terrain draw into {@code cmd}. Binds the appropriate pipeline variant, pushes
     * the scene UBO as set 0 binding 0 via {@code vkCmdPushDescriptorSetKHR}, and (unless
     * {@code visibleRegionCount == 0}) issues a single
     * {@code vkCmdDrawMeshTasksEXT(visibleRegionCount, 1, 1)} call.
     *
     * <p>The command buffer must already be inside a dynamic-rendering scope with inheritance
     * matching {@link #COLOR_FORMAT} and {@link #DEPTH_FORMAT}. Callers must also have pushed
     * combined-image-samplers to set 1 (see {@link #textureSetLayout}) beforehand.
     */
    public void record(VkCommandBuffer cmd,
                       SceneUniform sceneUniform,
                       int visibleRegionCount,
                       boolean renderFog,
                       long atlasView,
                       long atlasSampler,
                       long lightmapView,
                       long lightmapSampler) {
        recordWithPipeline(cmd, sceneUniform, visibleRegionCount,
            (renderFog ? pipelineFog : pipelineNoFog).handle(),
            atlasView, atlasSampler, lightmapView, lightmapSampler);
    }

    /** Translucent pass: same dispatch count but a blend-enabled pipeline driven by a task
     *  shader that reads the high-16 quad count from renderRanges.w. Call AFTER {@link #record}. */
    public void recordTranslucent(VkCommandBuffer cmd,
                                  SceneUniform sceneUniform,
                                  int visibleRegionCount,
                                  long atlasView, long atlasSampler,
                                  long lightmapView, long lightmapSampler) {
        recordWithPipeline(cmd, sceneUniform, visibleRegionCount,
            pipelineTranslucent.handle(),
            atlasView, atlasSampler, lightmapView, lightmapSampler);
    }

    private void recordWithPipeline(VkCommandBuffer cmd,
                                    SceneUniform sceneUniform,
                                    int visibleRegionCount,
                                    long pipeline,
                                    long atlasView,
                                    long atlasSampler,
                                    long lightmapView,
                                    long lightmapSampler) {
        if (closed) throw new IllegalStateException("PrimaryTerrainPass is closed");
        if (cmd == null) throw new NullPointerException("cmd");
        if (sceneUniform == null) throw new NullPointerException("sceneUniform");
        if (visibleRegionCount < 0) {
            throw new IllegalArgumentException("visibleRegionCount < 0: " + visibleRegionCount);
        }

        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

        // Single combined descriptor set (set=0) pushed in one go: UBO + atlas + lightmap.
        // Push-descriptor is only legal on one set per pipeline layout per the Vulkan spec;
        // unifying everything into one set avoids the cross-set invalidation that was breaking
        // the shader's texture binding.
        PushDescriptor pd = PushDescriptor.builder(pipelineLayout.handle(),
                VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, 0)
                .uniformBuffer(0,
                        sceneUniform.buffer().handle(),
                        0L,
                        SceneUniform.SCENE_UBO_SIZE);
        if (atlasView != 0L && atlasSampler != 0L) {
            pd.combinedImageSampler(1, atlasView, atlasSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            // Lightmap goes at binding=2. Fall back to atlas+sampler if lightmap isn't
            // available yet (first frames before GameRenderer.lightmap is initialized).
            long lView = lightmapView != 0L ? lightmapView : atlasView;
            long lSampler = lightmapSampler != 0L ? lightmapSampler : atlasSampler;
            pd.combinedImageSampler(2, lView, lSampler,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        pd.push(cmd);

        if (visibleRegionCount == 0) {
            return;
        }

        EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, visibleRegionCount, 1, 1);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Reverse construction order: pool → pipelines → pipeline layout → set layouts → modules.
        descriptorPool.close();
        pipelineTranslucent.close();
        pipelineFog.close();
        pipelineNoFog.close();
        pipelineLayout.close();
        // sceneUboSetLayout == textureSetLayout now (combined into one set). Close once.
        sceneUboSetLayout.close();
        fragModuleFog.close();
        fragModuleNoFog.close();
        meshTranslucentModule.close();
        taskTranslucentModule.close();
        meshModuleFog.close();
        meshModuleNoFog.close();
        taskModule.close();
    }
}
