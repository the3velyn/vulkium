#version 460
#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require


#include <vulkium:occlusion/scene.glsl>

#define ADD_SIZE (0.1f/16)

//TODO: maybe do multiple cubes per workgroup? this would increase utilization of individual sm's
layout(local_size_x = 8) in;
layout(triangles, max_vertices=8, max_primitives=12) out;

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

void main() {
    if (gl_LocalInvocationID.x == 0) {
        SetMeshOutputsEXT(8, 12);
    }
    barrier();

    //FIXME: It might actually be more efficent to just upload the region data straight into the ubo
    // this remove an entire level of indirection and also puts region data in the very fast path
    Region data = regionData.data[regionIndicies.data[gl_WorkGroupID.x]];//fetch the region data

    ivec3 pos = unpackRegionPosition(data);
    pos -= chunkPosition.xyz;
    pos -= unpackOriginOffsetId(unpackRegionTransformId(data));

    vec3 start = pos - ADD_SIZE;
    vec3 end = start + 1 + unpackRegionSize(data) + (ADD_SIZE*2);

    vec3 corner = vec3(((gl_LocalInvocationID.x&1)==0)?start.x:end.x, ((gl_LocalInvocationID.x&4)==0)?start.y:end.y, ((gl_LocalInvocationID.x&2)==0)?start.z:end.z);
    corner *= 16.0f;
    gl_MeshVerticesEXT[gl_LocalInvocationID.x].gl_Position = MVP*(getRegionTransformation(data)*vec4(corner, 1.0));

    int visibilityIndex = int(gl_WorkGroupID.x);

    regionVisibility.data[visibilityIndex] = uint8_t(0);

    gl_PrimitiveTriangleIndicesEXT[gl_LocalInvocationID.x] = CUBE_TRIS[gl_LocalInvocationID.x];
    gl_MeshPrimitivesEXT[gl_LocalInvocationID.x].gl_PrimitiveID = visibilityIndex;
    if (gl_LocalInvocationID.x < 4) {
        gl_PrimitiveTriangleIndicesEXT[8 + gl_LocalInvocationID.x] = CUBE_TRIS[8 + gl_LocalInvocationID.x];
        gl_MeshPrimitivesEXT[8 + gl_LocalInvocationID.x].gl_PrimitiveID = visibilityIndex;
    }
}
