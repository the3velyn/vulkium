package me.cortex.vulkium.vk;

import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;

/**
 * Vulkan shader stage + matching shaderc shader-kind. Covers the stages vulkium actually uses:
 * compute, vertex, fragment, task (mesh pipeline), mesh. Geometry/tessellation are not wired.
 */
public enum ShaderStage {
    COMPUTE(VK10.VK_SHADER_STAGE_COMPUTE_BIT, Shaderc.shaderc_compute_shader),
    VERTEX(VK10.VK_SHADER_STAGE_VERTEX_BIT, Shaderc.shaderc_vertex_shader),
    FRAGMENT(VK10.VK_SHADER_STAGE_FRAGMENT_BIT, Shaderc.shaderc_fragment_shader),
    TASK(0x00000040 /* VK_SHADER_STAGE_TASK_BIT_EXT */, Shaderc.shaderc_task_shader),
    MESH(0x00000080 /* VK_SHADER_STAGE_MESH_BIT_EXT */, Shaderc.shaderc_mesh_shader);

    public final int vkFlag;
    public final int shadercKind;

    ShaderStage(int vkFlag, int shadercKind) {
        this.vkFlag = vkFlag;
        this.shadercKind = shadercKind;
    }
}
