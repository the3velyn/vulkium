#version 460

// Temporal terrain task shader — VK_EXT_mesh_shader port from nvidium's NV_mesh_shader.
// See ../SHADERS_TODO.md for the translation recipe.

#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <vulkium:occlusion/scene.glsl>


//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;




bool shouldRenderVisible(uint sectionId) {
    uint8_t data = sectionVisibility.data[sectionId];
    return (data&uint8_t(3)) == uint8_t(1);//If the section was not visible last frame but is visible this frame, render it
}

#import <vulkium:terrain/task_common.glsl>

void main() {
    uint sectionId = gl_WorkGroupID.x;

    if (!shouldRenderVisible(sectionId)) {
        //Early exit if the section isnt visible
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    ivec4 header = sectionData.data[sectionId].header;
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;
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
