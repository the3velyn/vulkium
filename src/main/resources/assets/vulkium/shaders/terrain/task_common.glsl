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

    //TODO(vulkium): Put unsigned quads at the beginning? Should be cheaper.
    putBinData(idx, lastIndex, fr, fr + (ranges.w & 0xFFFF));

    payload.quadCount = lastIndex;

    // Caller does: EmitMeshTasksEXT(result, 1, 1);
    return (lastIndex + MESH_WORKLOAD_PER_INVOCATION - 1) / MESH_WORKLOAD_PER_INVOCATION;
}
