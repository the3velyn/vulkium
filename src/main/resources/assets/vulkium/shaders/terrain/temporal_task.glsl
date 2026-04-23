#version 460

// Temporal terrain task shader — VK_EXT_mesh_shader port from nvidium's NV_mesh_shader.
// See ../SHADERS_TODO.md for the translation recipe.

#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#define VULKIUM_TASK_STAGE 1
#include <vulkium:occlusion/scene.glsl>


//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;




bool shouldRenderVisible(uint sectionId) {
    uint8_t data = sectionVisibility.data[sectionId];
    return (data&uint8_t(3)) == uint8_t(1);//If the section was not visible last frame but is visible this frame, render it
}

#include <vulkium:terrain/task_common.glsl>

void main() {
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRenderVisible(sectionId)) {
        //Early exit if the section isnt visible
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    ivec4 header = sectionData.data[sectionId].header;
    ivec3 chunk;
    chunk.x = header.x >> 8;
    // chunk.y = chunkZ split decode — see task.glsl. Bits 18-25 of header.y hold the
    // post-sort section-id overwritten by RegionManager.removeSection's tail-compaction, so
    // chunkZ lives in header.y[8:17] + header.y[26:31] (16-bit signed = ±32K chunks).
    int chunkZLow  = (header.y >> 8) & 0x3FF;
    int chunkZHigh = (header.y >> 26) & 0x3F;
    int chunkZ16 = chunkZLow | (chunkZHigh << 10);
    chunk.y = (chunkZ16 << 16) >> 16;
    // chunk.z holds chunkY in low 9 bits (bits 8-16 of header.z), with pollution above
    // from the hide-bit and translucent count. Mask + sign-extend to recover signed chunkY.
    chunk.z = header.z >> 8;
    chunk.z &= 0x1ff;
    chunk.z <<= 32-9;
    chunk.z >>= 32-9;
    chunk -= chunkPosition.xyz;

    payload.transformationId = unpackRegionTransformId(regionData.data[sectionId>>8]);
    chunk -= unpackOriginOffsetId(payload.transformationId);

    payload.origin = vec3(chunk<<4);
    payload.baseOffset = uint(header.w);

    uint taskCount = populateTasks(chunk, uvec4(sectionData.data[sectionId].renderRanges));

    #ifdef STATISTICS_QUADS
    atomicAdd(statistics_buffer.data[2], payload.quadCount);
    #endif

    EmitMeshTasksEXT(taskCount, 1, 1);
}
