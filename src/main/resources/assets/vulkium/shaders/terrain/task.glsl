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
    // Region-level gate (populated by occlusion/region_cull.comp when cfg.enableHzbRegionCull
    // is on; stays all-0xFF otherwise). Cheap early-out: one buffer read, one AND, one branch.
    if ((regionVisibility.data[sectionId >> 8] & uint8_t(1)) == uint8_t(0)) {
        return false;
    }
    // Section-level gate (reserved for finer-grain culling; currently all-0xFF seeded).
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
    #else
    // Opaque compact-dispatch redirect: the CPU fills `opaqueDispatchList` with GPU-compact
    // section IDs for all live-and-visible sections (any order; opaque depth-test handles
    // sorting). If the pointer is null the dispatch reverts to the legacy
    // one-workgroup-per-slot pattern and sectionId = gl_WorkGroupID.x stays. With the list
    // populated, dispatch count drops from maxRegionIndex*256 to the populated-section
    // count — ~6-12× reduction in task-shader launches on typical scenes.
    if (uint64_t(opaqueDispatchList) != 0ul) {
        uint redirected = opaqueDispatchList.data[sectionId];
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
    ivec3 chunk;
    // chunk.x = chunkX (24-bit signed, full) from header.x>>8 — arithmetic shift sign-extends.
    chunk.x = header.x >> 8;
    // chunk.y = chunkZ. Bits 18-25 of header.y hold the post-sort local section-id (written by
    // RegionManager.removeSection's tail-compaction, read by translucent/task.glsl:42), so
    // chunkZ is split: low 10 bits at header.y[8:17], high 6 bits at header.y[26:31]. Previously
    // this decoded as header.y>>8 — contiguous 24-bit chunkZ — which aliased section-id into
    // the middle of chunkZ after any tail-compaction with sectionId > 0, producing garbage
    // chunkZ and catastrophic world-position scramble on scenes with many evictions.
    int chunkZLow  = (header.y >> 8) & 0x3FF;
    int chunkZHigh = (header.y >> 26) & 0x3F;
    int chunkZ16 = chunkZLow | (chunkZHigh << 10);
    chunk.y = (chunkZ16 << 16) >> 16; // sign-extend 16-bit chunkZ → ±32K chunks
    // chunk.z = chunkY in low 9 bits from header.z>>8, PLUS high bits polluted by hide-bit (17)
    //          and translucent-quad-count (bits 18-31 of header.z, which land at bits 10-23 of
    //          chunk.z after the shift). Mask to 9 bits and sign-extend so chunk.z holds chunkY.
    chunk.z = header.z >> 8;
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