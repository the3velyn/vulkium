#version 460

#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_arithmetic: require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#include <vulkium:occlusion/scene.glsl>
#include <vulkium:terrain/fog.glsl>
#include <vulkium:terrain/vertex_format.glsl>
#include <vulkium:terrain/task_common.glsl>


//It seems like for terrain at least, the sweet spot is ~16 quads per mesh invocation (even if the local size is not 32 )
layout(local_size_x = 16) in;
layout(triangles, max_vertices=64, max_primitives=32) out;

#ifdef RENDER_FOG
layout(location=1) out Interpolants {
    float fogLerp;
} OUT[];
#endif


//Do a binary search via global invocation index to determine the base offset
// Note, all threads in the work group are probably going to take the same path
uint getOffset() {
    uint gii = gl_GlobalInvocationID.x;

    //TODO: replace this with binary search
    if (gii < payload.binIa.x) {
        return payload.binVa.x + gii + payload.baseOffset;
    } else if (gii < payload.binIa.y) {
        return payload.binVa.y + (gii - payload.binIa.x) + payload.baseOffset;
    } else if (gii < payload.binIa.z) {
        return payload.binVa.z + (gii - payload.binIa.y) + payload.baseOffset;
    } else if (gii < payload.binIa.w) {
        return payload.binVa.w + (gii - payload.binIa.z) + payload.baseOffset;
    } else if (gii < payload.binIb.x) {
        return payload.binVb.x + (gii - payload.binIa.w) + payload.baseOffset;
    } else if (gii < payload.binIb.y) {
        return payload.binVb.y + (gii - payload.binIb.x) + payload.baseOffset;
    } else if (gii < payload.binIb.z) {
        return payload.binVb.z + (gii - payload.binIb.y) + payload.baseOffset;
    } else if (gii < payload.binIb.w) {
        return payload.binVb.w + (gii - payload.binIb.z) + payload.baseOffset;
    } else {
        return uint(-1);
    }
}

mat4 transformMat;

vec4 transformVertex(Vertex V) {
    // decodeVertexPosition returns section-local coords in MC order (X, Y, Z) because the
    // CPU repacker pulls MC vertex positions (px, py, pz) straight through. payload.origin
    // and subchunkOffset are in nvidium (X, Z, Y) order. Swap decoded vertex into nvidium
    // order so the addition is consistent, then swap back at MVP time for MC's Y-up projection.
    vec3 decoded = decodeVertexPosition(V);
    vec3 posNv = vec3(decoded.x, decoded.z, decoded.y) + payload.origin - subchunkOffset.xyz;
    return MVP*(transformMat * vec4(posNv.x, posNv.z, posNv.y, 1.0));
}

Vertex V0;
vec4 pV0;
Vertex V1;
vec4 pV1;
Vertex V2;
vec4 pV2;
Vertex V3;
vec4 pV3;



void putVertex(uint id, Vertex V) {
    #ifdef RENDER_FOG
    vec3 pos = decodeVertexPosition(V)+payload.origin;
    vec3 exactPos = pos+subchunkOffset.xyz;
    OUT[id].fogLerp = clamp(computeFogLerp(exactPos, isCylindricalFog, fogStart, fogEnd) * fogColour.a, 0, 1);
    #endif
}


// EXT mesh-shader translation of the NV version:
//   * NV let you trickle out primitives and set gl_PrimitiveCountNV at any time.
//   * EXT requires a single SetMeshOutputsEXT(vertCount, primCount) call before any
//     output write. We therefore run the per-quad visibility compute for every lane
//     first, subgroup-reduce to obtain workgroup totals, then emit.
void main() {
    uint id = getOffset();
    bool validQuad = (id != uint(-1));

    bool t0draw = false;
    bool t1draw = false;

    if (validQuad) {
        transformMat = mat4(1.0);

        //Load the data
        V0 = terrainData.data[(id<<2)+0];
        V1 = terrainData.data[(id<<2)+1];
        V2 = terrainData.data[(id<<2)+2];
        V3 = terrainData.data[(id<<2)+3];

        //Transform the vertices
        pV0 = transformVertex(V0);
        pV1 = transformVertex(V1);
        pV2 = transformVertex(V2);
        pV3 = transformVertex(V3);

        //Compute the bounding pixels of the 2 triangles in the quad. note, vertex 0 and 2 are the common verticies
        vec2 ssmin = ((pV0.xy/pV0.w)+1)*screenSize;
        vec2 ssmax = ssmin;

        vec2 point = ((pV2.xy/pV2.w)+1)*screenSize;
        ssmin = min(ssmin, point);
        ssmax = max(ssmax, point);

        point = ((pV1.xy/pV1.w)+1)*screenSize;
        vec2 t0min = min(ssmin, point);
        vec2 t0max = max(ssmax, point);

        point = ((pV3.xy/pV3.w)+1)*screenSize;
        vec2 t1min = min(ssmin, point);
        vec2 t1max = max(ssmax, point);

        //Possibly cull the triangles if they dont cover the center of a pixel on the screen (degen)
        // DIAG: bypass bbox cull unconditionally. If terrain appears → screenSize/bbox-cull still
        // broken. If still red, vertex data itself is bad.
        t0draw = true;
        t1draw = true;
    }

    uint triCnt = uint(t0draw) + uint(t1draw);
    uint vertCnt = (triCnt == 2) ? 4 : ((triCnt == 1) ? 3 : 0);

    // Compute per-lane slot bases + workgroup totals via subgroup reductions.
    uint triBase = subgroupExclusiveAdd(triCnt);
    uint vertBase = subgroupExclusiveAdd(vertCnt);
    uint totalTris = subgroupMax(triBase + triCnt);
    uint totalVerts = subgroupMax(vertBase + vertCnt);

    if (gl_LocalInvocationIndex == 0) {
        SetMeshOutputsEXT(totalVerts, totalTris);
    }
    barrier();

    if (triCnt == 0) return;

    uint triIndex = triBase;
    uint vertIndex = vertBase;

    //We have triangles to emit!
    // emit the constant vertices (0,2) that are needed for both triangles
    putVertex(vertIndex, V0); gl_MeshVerticesEXT[vertIndex++].gl_Position = pV0;
    putVertex(vertIndex, V2); gl_MeshVerticesEXT[vertIndex++].gl_Position = pV2;


    uint lodBias = hasMipping(V0)?0:1;
    uint alphaCutoff = rawVertexAlphaCutoff(V0);
    int primData = int((lodBias<<2)|alphaCutoff|(id<<4));

    if (t0draw) {
        putVertex(vertIndex, V1); gl_MeshVerticesEXT[vertIndex].gl_Position = pV1;
        // 0 1 2
        gl_PrimitiveTriangleIndicesEXT[triIndex] = uvec3(vertBase+0, vertIndex, vertBase+1);
        gl_MeshPrimitivesEXT[triIndex].gl_PrimitiveID = primData|(0<<3);
        triIndex++;
        vertIndex++;
    }

    if (t1draw) {
        putVertex(vertIndex, V3); gl_MeshVerticesEXT[vertIndex].gl_Position = pV3;
        // 2 3 0
        gl_PrimitiveTriangleIndicesEXT[triIndex] = uvec3(vertBase+1, vertIndex, vertBase+0);
        gl_MeshPrimitivesEXT[triIndex].gl_PrimitiveID = primData|(1<<3);
        triIndex++;
        vertIndex++;
    }
}
