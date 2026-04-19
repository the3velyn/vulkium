#version 460

// Primary terrain task shader — VK_EXT_mesh_shader port from nvidium's NV_mesh_shader.
// See ../SHADERS_TODO.md for the translation recipe.

#extension GL_EXT_mesh_shader : require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#define VULKIUM_TASK_STAGE 1
#include <vulkium:occlusion/scene.glsl>

layout(local_size_x=1) in;

bool shouldRenderVisible(uint sectionId) {
    return (sectionVisibility.data[sectionId] & uint8_t(1)) != uint8_t(0);
}

#include <vulkium:terrain/task_common.glsl>

void main() {
    // DIAG: unconditionally emit exactly one mesh workgroup per task dispatch so the mesh
    // stage always gets to run. Real task-shader logic restored once mesh pipeline is
    // proven visible.
    if (gl_WorkGroupID.x == 0u) {
        EmitMeshTasksEXT(1, 1, 1);
    } else {
        EmitMeshTasksEXT(0, 1, 1);
    }
}