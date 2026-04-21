// Task-shader → mesh-shader payload declaration.
//
// Nvidium used NV_mesh_shader, which exposed an inter-stage payload via
// `taskNV out/in Task { ... }`. Vulkan / VK_EXT_mesh_shader models this as a
// shared struct with the `taskPayloadSharedEXT` storage qualifier. The struct
// must be identical in the task and mesh shaders; both just declare it.
//
// Writes that used to be bare identifiers (`origin = ...; quadCount = ...;`) now
// go through the payload member (`payload.origin = ...;`). Emitting mesh tasks
// moves from assigning `gl_TaskCountNV = N;` to calling `EmitMeshTasksEXT(N, 1, 1);`
// which immediately returns from the task shader.

#define MESH_WORKLOAD_PER_INVOCATION 16

struct TaskPayload {
    vec3 origin;
    uint baseOffset;
    uint quadCount;
    uint transformationId;

    // Binary-search indexes and data (see mesh shader for usage).
    uvec4 binIa;
    uvec4 binIb;
    uvec4 binVa;
    uvec4 binVb;
};

taskPayloadSharedEXT TaskPayload payload;

// Write helpers — only valid in task shaders. Mesh shaders receive the payload
// read-only, so including this block when compiling a mesh stage is a compile error.
// Guarded by VULKIUM_TASK_STAGE which task.glsl / temporal_task.glsl / translucent/task.glsl
// define before including us.
#ifdef VULKIUM_TASK_STAGE

void putBinData(inout uint idx, inout uint lastIndex, uint offset, uint nextOffset) {
    uint len = nextOffset - offset;
    uint id = idx++;
    if (id < 4) {
        payload.binIa[id] = lastIndex + len;
        payload.binVa[id] = offset;
    } else {
        payload.binIb[id - 4] = lastIndex + len;
        payload.binVb[id - 4] = offset;
    }
    lastIndex += len;
}

/// Populate the task payload for a section, honoring per-face visibility.
/// Returns the number of mesh workgroups to emit; the caller issues
/// {@code EmitMeshTasksEXT(result, 1, 1)} (must be the last statement run in
/// the task shader's main() — EmitMeshTasksEXT returns immediately).
uint populateTasks(ivec3 relChunkPos, uvec4 ranges) {
    uint idx = 0;
    uint lastIndex = 0;

    payload.binIa = uvec4(0);
    payload.binIb = uvec4(0);

    uint fr = (ranges.w >> 16) & 0xFFFF;

    uint delta = (ranges.x & 0xFFFF);
    if (relChunkPos.x <= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += ranges.x & 0xFFFF;

    delta = ((ranges.x >> 16) & 0xFFFF);
    if (relChunkPos.y <= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += (ranges.x >> 16) & 0xFFFF;

    delta = ranges.y & 0xFFFF;
    if (relChunkPos.z <= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += ranges.y & 0xFFFF;

    delta = (ranges.y >> 16) & 0xFFFF;
    if (relChunkPos.x >= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += (ranges.y >> 16) & 0xFFFF;

    delta = ranges.z & 0xFFFF;
    if (relChunkPos.y >= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += ranges.z & 0xFFFF;

    delta = (ranges.z >> 16) & 0xFFFF;
    if (relChunkPos.z >= 0 && delta > 0) {
        putBinData(idx, lastIndex, fr, fr + delta);
    }
    fr += (ranges.z >> 16) & 0xFFFF;

    // Unsigned tail bin covers the span from fr (== sum of 6 face bins + starting offset)
    // through startingOffset + totalOpaqueCount. `ranges.w & 0xFFFF` is the TOTAL opaque
    // quad count (kept stable so translucent/task.glsl can use it as the translucent base
    // offset) — the unsigned bin size therefore = total - sum(face bins). We compute the
    // end directly from startingOffset + total so we don't carry the running sum around.
    uint startingOffset = (ranges.w >> 16) & 0xFFFF;
    uint totalOpaque = ranges.w & 0xFFFF;
    uint unsignedEnd = startingOffset + totalOpaque;
    if (fr < unsignedEnd) {
        putBinData(idx, lastIndex, fr, unsignedEnd);
    }

    payload.quadCount = lastIndex;

    // Caller does: EmitMeshTasksEXT(result, 1, 1);
    return (lastIndex + MESH_WORKLOAD_PER_INVOCATION - 1) / MESH_WORKLOAD_PER_INVOCATION;
}

#endif // VULKIUM_TASK_STAGE
