#version 460

#extension GL_ARB_shading_language_include : enable
#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#import <vulkium:occlusion/scene.glsl>

#define ADD_SIZE (0.1f)
layout(local_size_x = 8) in;
layout(triangles, max_vertices=8, max_primitives=12) out;

#import <vulkium:occlusion/section_raster/task_common.glsl>

// EXT version: NV used 4 per-lane PILUT writes over a flat index array
// (36 indices = 12 triangles). EXT requires one `uvec3` per triangle, so we
// precompute the 12 triangles of the AABB hull directly.
const uvec3 CUBE_TRIS[12] = {
    uvec3(0, 1, 2),
    uvec3(1, 3, 2),
    uvec3(0, 2, 6),
    uvec3(6, 4, 0),
    uvec3(0, 4, 5),
    uvec3(5, 1, 0),
    uvec3(1, 5, 7),
    uvec3(7, 3, 1),
    uvec3(4, 6, 7),
    uvec3(7, 5, 4),
    uvec3(2, 7, 6),
    uvec3(2, 3, 7),
};

//TODO: Check if the section can be culled via fog
void main() {
    int visibilityIndex = int(payload._visOutBase|gl_WorkGroupID.x);

    uint8_t lastData = sectionVisibility.data[visibilityIndex];

    ivec4 header = sectionData.data[payload._offset|gl_WorkGroupID.x].header;
    //If the section header was empty or the hide section bit is set, return

    //NOTE: technically this has the infinitly small probability of not rendering a block if the block is located at
    // 0,0,0 the only block in the chunk and the first thing in the buffer
    // to fix, also check that the ranges are null
    bool hidden = sectionEmpty(header) || (header.y&(1<<17)) != 0;

    if (hidden) {
        if (gl_LocalInvocationID.x == 0) {
            sectionVisibility.data[visibilityIndex] = uint8_t(0);
            SetMeshOutputsEXT(0, 0);
        }
        return;
    }

    if (gl_LocalInvocationID.x == 0) {
        SetMeshOutputsEXT(8, 12);
    }
    barrier();

    vec3 mins = (header.xyz&0xF)-ADD_SIZE;
    vec3 maxs = mins+((header.xyz>>4)&0xF)+1+(ADD_SIZE*2);
    ivec3 chunk = ivec3(header.xyz)>>8;
    chunk.y &= 0x1ff;
    chunk.y <<= 32-9;
    chunk.y >>= 32-9;

    ivec3 relativeChunkPos = (chunk + payload.chunkShift);
    vec3 corner = vec3(relativeChunkPos<<4);
    vec3 cornerCopy = corner;

    //TODO: try mix instead or something other than just ternaries, i think they get compiled to a cmov type instruction but not sure
    corner += vec3(((gl_LocalInvocationID.x&1)==0)?mins.x:maxs.x, ((gl_LocalInvocationID.x&4)==0)?mins.y:maxs.y, ((gl_LocalInvocationID.x&2)==0)?mins.z:maxs.z);
    gl_MeshVerticesEXT[gl_LocalInvocationID.x].gl_Position = (MVP*(payload.regionTransform*vec4(corner, 1.0)));

    int prim_payload = (visibilityIndex<<8)|int(((uint(lastData))<<1)&0xff)|1;

    gl_PrimitiveTriangleIndicesEXT[gl_LocalInvocationID.x] = CUBE_TRIS[gl_LocalInvocationID.x];
    gl_MeshPrimitivesEXT[gl_LocalInvocationID.x].gl_PrimitiveID = prim_payload;
    if (gl_LocalInvocationID.x < 4) {
        gl_PrimitiveTriangleIndicesEXT[8 + gl_LocalInvocationID.x] = CUBE_TRIS[8 + gl_LocalInvocationID.x];
        gl_MeshPrimitivesEXT[8 + gl_LocalInvocationID.x].gl_PrimitiveID = prim_payload;
    }
    if (gl_LocalInvocationID.x == 0) {
        cornerCopy += subchunkOffset.xyz;
        vec3 minPos = mins + cornerCopy;
        vec3 maxPos = maxs + cornerCopy;
        bool isInSection = all(lessThan(minPos, vec3(ADD_SIZE))) && all(lessThan(vec3(-ADD_SIZE), maxPos));

        //Shift and set, this gives us a bonus of having the last 8 frames as visibility history
        sectionVisibility.data[visibilityIndex] = uint8_t(lastData<<1) | uint8_t(isInSection?1:0);//Inject visibility aswell
    }
}
