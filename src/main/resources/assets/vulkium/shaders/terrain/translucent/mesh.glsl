#version 460

#define UNROLL_LOOP
#extension GL_EXT_mesh_shader : require

#extension GL_KHR_shader_subgroup_arithmetic : require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_KHR_shader_subgroup_vote : require

#include <vulkium:occlusion/scene.glsl>
#include <vulkium:terrain/fog.glsl>
#include <vulkium:terrain/vertex_format.glsl>


#ifdef TRANSLUCENCY_SORTING_QUADS
vec3 depthPos = vec3(0);
shared float depthBuffers[32];
#endif

layout(local_size_x = 32) in;
layout(triangles, max_vertices=128, max_primitives=64) out;

//originAndBaseData.w is in quad count space, so is endIdx
#include <vulkium:terrain/translucent/task_common.glsl>

layout(location=1) out Interpolants {
#ifdef RENDER_FOG
    float fogLerp;
#endif
    vec2 uv;
    vec3 v_colour;
} OUT[];

layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
    return vec4(texture(tex_light, uv).rgb, 1);
}

void emitQuadIndicies(uint primBase, uint vertexBase) {
    // EXT: one write per triangle (uvec3) instead of 6 per-index writes.
    gl_PrimitiveTriangleIndicesEXT[primBase+0] = uvec3(vertexBase+0, vertexBase+1, vertexBase+2);
    gl_PrimitiveTriangleIndicesEXT[primBase+1] = uvec3(vertexBase+2, vertexBase+3, vertexBase+0);
}

void emitVertex(uint outId, uint vertexBaseId, uint innerId) {
    Vertex V = terrainData.data[vertexBaseId + innerId];
    vec3 pos = decodeVertexPosition(V)+payload.originAndBaseData.xyz;
    gl_MeshVerticesEXT[outId].gl_Position = MVP*vec4(pos,1.0);


    // `pos` is already camera-exact-relative in MC axis (MVP is projection × viewRotation
    // only, no translation — so pos must be camera-relative for the draw to land correctly).
    // Use it directly for fog; the prior `pos + subchunkOffset` double-offset and mixed
    // MC+nvidium axes, which snapped fog onto the chunk grid and skewed distance.
    #ifdef RENDER_FOG
    OUT[outId].fogLerp = clamp(computeFogLerp(pos, fogEnvStart, fogEnvEnd, fogRenderStart, fogRenderEnd) * fogColour.a, 0, 1);
    #endif

    // Keep the old exactPos expression solely for the per-quad depth-accumulation sort
    // (QUADS-level translucency). The sort uses relative magnitudes between quads, which
    // a constant per-frame offset doesn't disturb — not worth changing now since any
    // correctness regression would land directly in the translucent ordering path.
    vec3 exactPos = pos + subchunkOffset.xyz;
    OUT[outId].uv = decodeVertexUV(V);

    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    OUT[outId].v_colour = tint.rgb;

    #ifdef TRANSLUCENCY_SORTING_QUADS
    depthPos += exactPos;
    #endif

}

#ifdef TRANSLUCENCY_SORTING_QUADS
void swapQuads(uint idxA, uint idxB) {
    if (idxA == idxB) {
        return;
    }

    Vertex A0 = terrainData.data[(idxA<<2)+0];
    Vertex A1 = terrainData.data[(idxA<<2)+1];
    Vertex A2 = terrainData.data[(idxA<<2)+2];
    Vertex A3 = terrainData.data[(idxA<<2)+3];
    Vertex B0 = terrainData.data[(idxB<<2)+0];
    Vertex B1 = terrainData.data[(idxB<<2)+1];
    Vertex B2 = terrainData.data[(idxB<<2)+2];
    Vertex B3 = terrainData.data[(idxB<<2)+3];
    //groupMemoryBarrier();
    //memoryBarrier();
    //barrier();
    terrainData.data[(idxA<<2)+0] = B0;
    terrainData.data[(idxA<<2)+1] = B1;
    terrainData.data[(idxA<<2)+2] = B2;
    terrainData.data[(idxA<<2)+3] = B3;
    terrainData.data[(idxB<<2)+0] = A0;
    terrainData.data[(idxB<<2)+1] = A1;
    terrainData.data[(idxB<<2)+2] = A2;
    terrainData.data[(idxB<<2)+3] = A3;
    //groupMemoryBarrier();
    //memoryBarrier();
    //barrier();
}

void performTranslucencySort() {
    uint baseQuadPtr = floatBitsToUint(payload.originAndBaseData.w) + (gl_WorkGroupID.x<<5);

    float depth = dot(depthPos, depthPos) * ((1/4f)*(1/4f));
    depthBuffers[gl_LocalInvocationID.x] = depth;

    if (gl_GlobalInvocationID.x < payload.jiggle) {
        //If we are in the jiggle index dont attempt to swap else we start rendering garbage data
        depthBuffers[gl_LocalInvocationID.x] = -9999f;
    }

    groupMemoryBarrier();
    memoryBarrier();
    barrier();
    //TODO: use subgroup ballot to check if all the quads are already sorted, if they are dont perform sort op

    //Only use 16 threads to sort all 32 data
    if (gl_LocalInvocationID.x < 16) {
        uint idA = (gl_LocalInvocationID.x<<1);
        uint idB = (gl_LocalInvocationID.x<<1)+1;
        float a = depthBuffers[idA];
        float b = depthBuffers[idB];

        if (a > 0.0001f &&  b > 0.0001f && a < b) {
            swapQuads(idA + baseQuadPtr, idB + baseQuadPtr);
        }
    }
}
#endif

// EXT mesh-shader translation:
//   NV let us set gl_PrimitiveCountNV at any time with a dynamic per-workgroup
//   tail value. EXT requires a single SetMeshOutputsEXT before any output
//   writes. Since every active lane emits exactly 4 verts + 2 tris (invalid
//   lanes emit nothing), we compute the count on lane 0 from quadCount and
//   workgroup id, then barrier before writes.
void main() {
    #ifdef TRANSLUCENCY_SORTING_QUADS
    depthBuffers[gl_LocalInvocationID.x] = -99999999f;
    #endif

    bool validQuad = (gl_GlobalInvocationID.x < payload.quadCount);

    // Remaining quads in this workgroup: min(quadCount - workgroup_base, 32).
    // That's also the count of valid lanes.
    uint activeQuads = min(uint(max(int(payload.quadCount) - int(gl_WorkGroupID.x<<5), 0)), 32u);
    uint totalVerts = activeQuads << 2;
    uint totalPrims = activeQuads << 1;

    if (gl_LocalInvocationIndex == 0) {
        SetMeshOutputsEXT(totalVerts, totalPrims);
    }
    barrier();

    if (!validQuad) {
        #ifdef TRANSLUCENCY_SORTING_QUADS
        // We still participate in the sort barrier below; fall through.
        #else
        return;
        #endif
    }

    uint primBase = gl_LocalInvocationID.x * 2;
    uint vertexBase = gl_LocalInvocationID.x<<2;

    //Each pair of meshlet invokations emits 4 vertices each and 2 primative each
    uint id = (floatBitsToUint(payload.originAndBaseData.w) + gl_GlobalInvocationID.x)<<2;

    #ifdef TRANSLUCENCY_SORTING_QUADS
    //If we are at the start, dont want to render as it contains garbled data (out of bounds)
    if (validQuad && gl_GlobalInvocationID.x < payload.jiggle) {
        gl_MeshVerticesEXT[vertexBase+0].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesEXT[vertexBase+1].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesEXT[vertexBase+2].gl_Position = vec4(1,1,1,-1);
        gl_MeshVerticesEXT[vertexBase+3].gl_Position = vec4(1,1,1,-1);

    } else if (validQuad) {
        emitVertex(vertexBase+0, id, 0);
        emitVertex(vertexBase+1, id, 1);
        emitVertex(vertexBase+2, id, 2);
        emitVertex(vertexBase+3, id, 3);
    }
    barrier();
    memoryBarrierShared();

    performTranslucencySort();

    if (!validQuad) return;
    #else
    emitVertex(vertexBase+0, id, 0);
    emitVertex(vertexBase+1, id, 1);
    emitVertex(vertexBase+2, id, 2);
    emitVertex(vertexBase+3, id, 3);
    #endif

    emitQuadIndicies(primBase, vertexBase);

    gl_MeshPrimitivesEXT[primBase+0].gl_PrimitiveID = int((id>>2)<<4)|(0<<3);
    gl_MeshPrimitivesEXT[primBase+1].gl_PrimitiveID = int((id>>2)<<4)|(1<<3);
}
