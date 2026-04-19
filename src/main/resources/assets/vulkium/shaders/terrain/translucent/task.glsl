#version 460

#extension GL_ARB_shading_language_include : enable
#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <vulkium:occlusion/scene.glsl>

#define MESH_WORKLOAD_PER_INVOCATION 32

//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;

#import <vulkium:terrain/translucent/task_common.glsl>

bool shouldRender(uint sectionId) {
    //Check visibility
    return (sectionVisibility.data[sectionId]&uint8_t(1)) != uint8_t(0);
}

void main() {
    uint sectionId = gl_WorkGroupID.x;
    #ifdef TRANSLUCENCY_SORTING_SECTIONS
    //Compute indirection for translucency sorting
    {
        ivec4 header = sectionData.data[sectionId].header;
        //If the section is empty, we dont care about it at all, so ignore it and return
        if (sectionEmpty(header)) {
            EmitMeshTasksEXT(0, 1, 1);
            return;
        }
        //Compute the redirected section index
        sectionId &= ~0xFF;
        sectionId |= uint((header.y>>18)&0xFF);
    }
    #endif

    if (!shouldRender(sectionId)) {
        //Early exit if the section isnt visible
        //TODO: also early exit if there are no translucents to render
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    ivec4 header = sectionData.data[sectionId].header;
    uint baseDataOffset = uint(header.w);
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;
    payload.originAndBaseData.xyz = vec3((chunk - chunkPosition.xyz)<<4);


    payload.quadCount = ((sectionData.data[sectionId].renderRanges.w>>16)&0xFFFF);
    #ifdef TRANSLUCENCY_SORTING_QUADS
    payload.jiggle = uint8_t(min(payload.quadCount>>1,(uint(frameId)&1)));//Jiggle by 1 quads (either 0 or 1)//*15
    //payload.jiggle = uint8_t(0);
    payload.quadCount += payload.jiggle;
    payload.originAndBaseData.w = uintBitsToFloat(baseDataOffset - uint(payload.jiggle));
    #else
    payload.originAndBaseData.w = uintBitsToFloat(baseDataOffset);
    #endif

    //Emit enough mesh shaders such that max(gl_GlobalInvocationID.x)>=quadCount
    uint taskCount = (payload.quadCount+MESH_WORKLOAD_PER_INVOCATION-1)/MESH_WORKLOAD_PER_INVOCATION;

    #ifdef STATISTICS_QUADS
    atomicAdd(statistics_buffer.data[2], payload.quadCount);
    #endif

    #ifdef STATISTICS_SECTIONS
    atomicAdd(statistics_buffer.data[1], 1);
    #endif

    EmitMeshTasksEXT(taskCount, 1, 1);
}
