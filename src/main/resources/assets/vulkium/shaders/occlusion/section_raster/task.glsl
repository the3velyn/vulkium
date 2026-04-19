#version 460

#extension GL_ARB_shading_language_include : enable
#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <vulkium:occlusion/scene.glsl>

//This is 1 since each task shader workgroup -> multiple meshlets. its not each globalInvocation (afaik)
layout(local_size_x=1) in;

#import <vulkium:occlusion/section_raster/task_common.glsl>

void main() {
    //TODO: see whats faster, atomicAdd (for mdic) or dispatching alot of empty calls (mdi)
    //TODO: experiment with emitting 8 workgroups with the 8th always being 0
    // doing so would enable to batch memory write 2 commands
    // thus taking 4 mem moves instead of 7

    //Emit 7 workloads per chunk
    uint cmdIdx = gl_WorkGroupID.x;
    uint transCmdIdx = (uint(regionCount) - gl_WorkGroupID.x) - 1;

    //Early exit if the region wasnt visible
    if (regionVisibility.data[gl_WorkGroupID.x] == uint8_t(0)) {
        terrainCommandBuffer.data[cmdIdx] = uvec2(0);
        translucencyCommandBuffer.data[transCmdIdx] = uvec2(0);
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    #ifdef STATISTICS_REGIONS
    atomicAdd(statistics_buffer.data[0], 1);
    #endif

    //FIXME: It might actually be more efficent to just upload the region data straight into the ubo
    uint32_t offset = regionIndicies.data[gl_WorkGroupID.x];
    Region data = regionData.data[offset];
    int count = unpackRegionCount(data)+1;

    //Write in order
    payload._visOutBase = offset<<8;//This makes checking visibility very fast and quick in the compute shader
    payload._offset = offset<<8;
    payload.regionTransform = getRegionTransformation(data);

    payload.chunkShift = (-chunkPosition.xyz) - unpackOriginOffsetId(unpackRegionTransformId(data));

    terrainCommandBuffer.data[cmdIdx] = uvec2(uint32_t(count), payload._visOutBase);
    //TODO: add a bit to the region header to determine whether or not a region has any translucent
    // sections, if it doesnt, write 0 to the command buffer
    translucencyCommandBuffer.data[transCmdIdx] = uvec2(uint32_t(count), payload._visOutBase);

    EmitMeshTasksEXT(uint(count), 1, 1);
}
