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
    // DIAG: unconditionally emit one mesh workgroup per task workgroup so we can tell whether
    // the whole mesh pipeline is rasterizing. Revert once the real path is producing pixels.
    if (gl_WorkGroupID.x == 0u) {
        EmitMeshTasksEXT(1, 1, 1);
    } else {
        EmitMeshTasksEXT(0, 1, 1);
    }
    return;
    // --- real path below (dead code under DIAG) ---
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRenderVisible(sectionId)) {
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    #ifdef STATISTICS_SECTIONS
    atomicAdd(statistics_buffer.data[1], 1);
    #endif

    ivec4 header = sectionData.data[sectionId].header;
    ivec3 chunk = ivec3(header.xyz) >> 8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32 - 9;
    chunk.y >>= 32 - 9;
    chunk -= chunkPosition.xyz;
    payload.transformationId = unpackRegionTransformId(regionData.data[sectionId >> 8]);
    chunk -= unpackOriginOffsetId(payload.transformationId);

    payload.origin = vec3(chunk << 4);
    payload.baseOffset = uint(header.w);

    uint taskCount = populateTasks(chunk, uvec4(sectionData.data[sectionId].renderRanges));

    #ifdef STATISTICS_QUADS
    atomicAdd(statistics_buffer.data[2], payload.quadCount);
    #endif

    EmitMeshTasksEXT(taskCount, 1, 1);
}