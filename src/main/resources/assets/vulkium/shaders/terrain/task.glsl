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
    uint sectionId = gl_WorkGroupID.x;
    #ifdef TRANSLUCENT_PASS
    // Back-to-front redirect: the CPU fills `sortingRegionList` with GPU-compact section IDs
    // ordered farthest-first, terminated/padded with 0xFFFFFFFF. If the list is unavailable
    // (null pointer), the dispatch falls back to linear section order. When redirected, the
    // new sectionId is the one read for all subsequent header/ranges accesses below.
    if (uint64_t(sortingRegionList) != 0ul) {
        uint redirected = sortingRegionList.data[sectionId];
        if (redirected == 0xFFFFFFFFu) {
            EmitMeshTasksEXT(0, 1, 1);
            return;
        }
        sectionId = redirected;
    }
    #endif

    if (!shouldRenderVisible(sectionId)) {
        EmitMeshTasksEXT(0, 1, 1);
        return;
    }

    ivec4 header = sectionData.data[sectionId].header;
    ivec3 chunk = ivec3(header.xyz) >> 8;
    // chunk.x = chunkX (24-bit signed, full) from header.x>>8 — done, arithmetic shift sign-extends.
    // chunk.y = chunkZ (24-bit signed, full) from header.y>>8 — done.
    // chunk.z = chunkY in low 9 bits from header.z>>8, PLUS high bits polluted by hide-bit (17)
    //          and translucent-quad-count (bits 18-31 of header.z, which land at bits 10-23 of
    //          chunk.z after the shift). Mask to 9 bits and sign-extend so chunk.z holds chunkY.
    //          (nvidium's shader masks `chunk.y` here, but that's chunkZ in its layout; the fix
    //           is on chunk.z for our pack order header.z=chunkY.)
    chunk.z &= 0x1ff;
    chunk.z <<= 32 - 9;
    chunk.z >>= 32 - 9;
    chunk -= chunkPosition.xyz;
    payload.transformationId = unpackRegionTransformId(regionData.data[sectionId >> 8]);
    chunk -= unpackOriginOffsetId(payload.transformationId);

    payload.origin = vec3(chunk << 4);

    // renderRanges.w low 16 = opaque quad count (original semantic preserved;
    // populateTasks reads ranges.w high 16 as its `fr` base offset — leaving that at 0).
    // Translucent quad count lives in header.z bits 18-31 (14 bits).
    uvec4 ranges = uvec4(sectionData.data[sectionId].renderRanges);
    uint opaqueQuads = ranges.w & 0xFFFFu;
    uint translucentQuads = (uint(header.z) >> 18) & 0x3FFFu;

    #ifdef TRANSLUCENT_PASS
    payload.baseOffset = uint(header.w) + opaqueQuads;
    payload.quadCount = translucentQuads;
    payload.binIa = uvec4(0);
    payload.binIb = uvec4(0);
    payload.binVa = uvec4(0);
    payload.binVb = uvec4(0);
    // Single bin covering the whole translucent range.
    payload.binIa.x = translucentQuads;
    payload.binVa.x = 0u;
    uint taskCount = (translucentQuads + MESH_WORKLOAD_PER_INVOCATION - 1u)
                      / MESH_WORKLOAD_PER_INVOCATION;
    EmitMeshTasksEXT(taskCount, 1, 1);
    #else
    payload.baseOffset = uint(header.w);
    // Opaque path — existing populateTasks handles binning from renderRanges.xyz.
    // Replace ranges.w with opaque-only low 16 (high 16 = translucent not relevant here).
    uvec4 opaqueRanges = uvec4(ranges.x, ranges.y, ranges.z, opaqueQuads);
    uint taskCount = populateTasks(chunk, opaqueRanges);
    EmitMeshTasksEXT(taskCount, 1, 1);
    #endif
}