// Translucent task-shader → mesh-shader payload declaration.
//
// This payload is distinct from the main terrain payload in
// `terrain/task_common.glsl`: translucent quads only need origin + base offset
// + quad count (and an optional jiggle byte for TRANSLUCENCY_SORTING_QUADS).
//
// See `terrain/task_common.glsl` for the generic translation notes
// (taskNV → taskPayloadSharedEXT, gl_TaskCountNV → EmitMeshTasksEXT, etc.).

struct TranslucentTaskPayload {
    vec4 originAndBaseData;
    uint quadCount;
    #ifdef TRANSLUCENCY_SORTING_QUADS
    uint8_t jiggle;
    #endif
};

taskPayloadSharedEXT TranslucentTaskPayload payload;
