// Section-raster task-shader → mesh-shader payload declaration.
//
// Used by `occlusion/section_raster/task.glsl` (producer) and
// `occlusion/section_raster/mesh.glsl` (consumer). See
// `terrain/task_common.glsl` for the general translation notes.

struct SectionRasterTaskPayload {
    uint32_t _visOutBase; // Base offset for visibility output
    uint32_t _offset;     // Start offset for regions
    mat4 regionTransform;
    ivec3 chunkShift;
};

taskPayloadSharedEXT SectionRasterTaskPayload payload;
