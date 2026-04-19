// Shared scene-data include. Ported from nvidium's NV_shader_buffer_load / NV_bindless approach
// to standard Vulkan GL_EXT_buffer_reference. Every bindless pointer becomes a typed
// `buffer_reference` block; access changes from `ptr[i]` to `ptr.data[i]`.

#extension GL_EXT_buffer_reference : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_shader_explicit_arithmetic_types : require

#define Vertex uvec4

// -----------------------------------------------------------------------------
// Structs (unchanged from nvidium — same bit layout in memory)
// -----------------------------------------------------------------------------

// Packed section header. Laid out for section rasterizer cache hit rate.
struct Section {
    ivec4 header;
    //Header.x -> 0-3=offsetx 4-7=sizex 8-31=chunk x
    //Header.y -> 0-3=offsetz 4-7=sizez 8-31=chunk z 18-25=local-section-id-post-sort
    //Header.z -> 0-3=offsety 4-7=sizey 8-15=chunk y  17=hide-bit
    //Header.w -> quad offset
    ivec4 renderRanges;
};

struct Region {
    uint64_t a;
    uint64_t b;
};

ivec3 unpackRegionSize(Region region) {
    return ivec3((region.a >> 59) & 7, region.a >> 62, (region.a >> 56) & 7);
}

uint unpackRegionTransformId(Region region) {
    return uint((region.b >> (64-24-10)) & ((1<<10)-1));
}

ivec3 unpackRegionPosition(Region region) {
    int x = int(int64_t(region.a << (64-24-24)) >> (64-24));
    int y = (int(region.a) << 8) >> 8;
    int z = int(int64_t(region.b) >> (64-24));
    return ivec3(x, y, z);
}

int unpackRegionCount(Region region) {
    return int((region.a >> 48) & 255);
}

bool sectionEmpty(ivec4 header) {
    header.y &= ~0x1FF << 17;
    return header == ivec4(0);
}

// -----------------------------------------------------------------------------
// Bindless pointer types — each was `TYPE *` on NV_shader_buffer_load.
// Vulkan equivalent: GL_EXT_buffer_reference. The blocks below declare 8-byte
// pointer types; UBO members below hold them the same way they held the NV
// pointers (8 bytes each).
// -----------------------------------------------------------------------------

layout(buffer_reference, std430, buffer_reference_align=2) readonly restrict buffer RegionIndicesPtr {
    uint16_t data[];
};

layout(buffer_reference, std430, buffer_reference_align=8) readonly restrict buffer RegionDataPtr {
    Region data[];
};

layout(buffer_reference, std430, buffer_reference_align=16) restrict buffer SectionDataPtr {
    Section data[];
};

layout(buffer_reference, std430, buffer_reference_align=1) restrict buffer VisibilityPtr {
    uint8_t data[];
};

layout(buffer_reference, std430, buffer_reference_align=8) writeonly restrict buffer CommandBufferPtr {
    uvec2 data[];
};

layout(buffer_reference, std430, buffer_reference_align=2) readonly restrict buffer SortingRegionListPtr {
    uint16_t data[];
};

layout(buffer_reference, std430, buffer_reference_align=16) restrict buffer TerrainDataPtr {
    Vertex data[];
};

layout(buffer_reference, std430, buffer_reference_align=16) readonly restrict buffer TransformationArrayPtr {
    mat4 data[];
};

layout(buffer_reference, std430, buffer_reference_align=8) readonly restrict buffer OriginArrayPtr {
    uint64_t data[];
};

layout(buffer_reference, std430, buffer_reference_align=4) restrict buffer StatisticsBufferPtr {
    uint32_t data[];
};

// -----------------------------------------------------------------------------
// Scene UBO. Binding 0 / set 0. MC 26.2 Vulkan runs with push-descriptors so
// this is populated via `vkCmdPushDescriptorSetKHR` once per render phase.
// -----------------------------------------------------------------------------

layout(std140, binding=0) uniform SceneData {
    // align(16)
    mat4 MVP;
    #ifdef RENDER_FOG
    mat4 MVPInv;
    #endif
    ivec4 chunkPosition;
    vec4 subchunkOffset;
    vec4 fogColour;

    // align(8) — pointers (8 bytes each under GL_EXT_buffer_reference, same as the NV layout)
    RegionIndicesPtr regionIndicies;
    RegionDataPtr regionData;
    SectionDataPtr sectionData;
    VisibilityPtr regionVisibility;
    VisibilityPtr sectionVisibility;
    CommandBufferPtr terrainCommandBuffer;
    CommandBufferPtr translucencyCommandBuffer;
    SortingRegionListPtr sortingRegionList;
    TerrainDataPtr terrainData;
    TransformationArrayPtr transformationArray;
    OriginArrayPtr originArray;
    StatisticsBufferPtr statistics_buffer;

    vec2 screenSize;
    float fogStart;
    float fogEnd;
    bool isCylindricalFog;

    // align(2)
    uint16_t regionCount;
    // align(1)
    uint8_t frameId;
};

// -----------------------------------------------------------------------------
// Helpers — same semantics as nvidium, access-pattern adjusted to `.data[]`.
// -----------------------------------------------------------------------------

mat4 getRegionTransformation(Region region) {
    return transformationArray.data[unpackRegionTransformId(region)];
}

ivec3 unpackOriginOffsetId(uint id) {
    uint64_t val = originArray.data[id];
    int x = (int(uint(val & 0x1ffffff)) << 7) >> 7;
    int y = (int(uint((val >> 50) & 0x3fff)) << 18) >> 18;
    int z = (int(uint((val >> 25) & 0x1ffffff)) << 7) >> 7;
    return ivec3(x, y, z);
}
