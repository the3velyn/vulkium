// Sodium CompactChunkVertex decoders. Vertex struct + storage layout live in
// occlusion/scene.glsl. See the header comment there for the byte layout.
//
// Position decode notes:
//   Sodium packs each component into 20 bits, split across positionHi (high 10) and
//   positionLo (low 10), all three components in one dword each. So:
//     posLo: [X_lo10 | Y_lo10 << 10 | Z_lo10 << 20]
//     posHi: [X_hi10 | Y_hi10 << 10 | Z_hi10 << 20]
//   Reassemble: quantC = (hi << 10) | lo. Dequantize: world = quant/2^20 * 32 - 8.
//   (POSITION_MAX_VALUE = 1<<20 = 1048576; MODEL_RANGE = 32; MODEL_ORIGIN = 8 — see
//   sodium's CompactChunkVertex.java).
#define SODIUM_POS_INV_SCALE (32.0 / 1048576.0)
#define SODIUM_POS_ORIGIN    8.0

// Texture coord: low 15 bits = quantized [0, 32768) → UV in [0, 1). Bit 15 is a sign
// flag for a sub-texel bias Sodium adds to avoid bleeding; we discard it (sub-pixel
// bias matters when the GPU later re-samples per-pixel UV, not when the vertex stage
// reads the value). vulkium's existing TEXTURE_MAX_SCALE has always been 32768.
#ifndef TEXTURE_MAX_SCALE
#define TEXTURE_MAX_SCALE 32768.0
#endif

#define COLOR_SCALE (1.0 / 255.0)

vec3 decodeVertexPosition(Vertex v) {
    uint posLo = v.position.x;
    uint posHi = v.position.y;
    uvec3 quantized = uvec3(
        ((posHi >>  0) & 0x3FFu) << 10 | ((posLo >>  0) & 0x3FFu),
        ((posHi >> 10) & 0x3FFu) << 10 | ((posLo >> 10) & 0x3FFu),
        ((posHi >> 20) & 0x3FFu) << 10 | ((posLo >> 20) & 0x3FFu)
    );
    return vec3(quantized) * SODIUM_POS_INV_SCALE - SODIUM_POS_ORIGIN;
}

// Sodium pre-multiplies RGB by AO at buffer-write time (ColorARGB.mulRGB). The lit
// terrain shader path multiplies by the lightmap sample; AO baked into RGB just makes
// the AO occluded fragments darker than the lightmap alone would.
//
// Byte layout (LE): byte0=B, byte1=G, byte2=R, byte3=A. (ARGB int stored as A in MSB.)
// We pass alpha through; for SOLID/CUTOUT it's 0xFF, for TRANSLUCENT Sodium carries the
// vanilla per-quad alpha needed for blending.
vec4 decodeVertexColour(Vertex v) {
    uint c = v.colour;
    return vec4(
        float((c >> 16) & 0xFFu),
        float((c >>  8) & 0xFFu),
        float((c >>  0) & 0xFFu),
        float((c >> 24) & 0xFFu)
    ) * COLOR_SCALE;
}

vec2 decodeVertexUV(Vertex v) {
    uint t = v.texCoord;
    // Mask the bias sign bits (15 of U dword, 15 of V dword) — we only want the
    // 15-bit quantized magnitude. The bias offset is a sub-texel epsilon that doesn't
    // help at this stage; matters more when Sodium's GL path samples per-pixel.
    return vec2(
        float((t >>  0) & 0x7FFFu),
        float((t >> 16) & 0x7FFFu)
    ) * (1.0 / TEXTURE_MAX_SCALE);
}

// Sodium's material byte: bit 0 = useMipmaps; bits 1-7 = alphaCutoff enum ordinal.
// hasMipping returns true when mipmaps are enabled for this vertex's render layer.
bool hasMipping(Vertex v) {
    return (v.lightData & (1u << 16)) != 0u;
}

// 2-bit alphaCutoff index, used as the gl_PrimitiveID low bits so the fragment shader
// can pick a discard threshold without an extra interpolant. Sodium stores up to a
// 7-bit ordinal in [17:23]; we take the low 2 bits here because frag.frag's selector
// is `gl_PrimitiveID & 3u`. If Sodium ever adds a 4+ cutoff variant we'll need to
// widen primData's cutoff field.
uint rawVertexAlphaCutoff(Vertex v) {
    return (v.lightData >> 17) & 3u;
}

float decodeVertexAlphaCutoff(Vertex v) {
    return (float[](0.0f, 0.5f, 0.0f, 0.0f))[rawVertexAlphaCutoff(v)];
}

float getVertexAlphaCutoff(uint cutoffIdx) {
    return (float[](0.0f, 0.5f, 0.0f, 0.0f))[cutoffIdx];
}

vec2 decodeLightUV(Vertex v) {
    // Sodium clamps to [8, 248] at build time. Same lightmap sample formula vanilla
    // uses: uv = light/256 + 0.5/16, then clamp to [0.5/16, 15.5/16]. The [8,248]
    // pre-clamp narrows the resulting UV to about [0.0625, 0.969] — well inside the
    // clamped range — so the clamp() below is a no-op in practice. Kept verbatim so
    // any future light path that bypasses Sodium still goes through the safe sample.
    uvec2 light = uvec2(
        (v.lightData >> 0) & 0xFFu,
        (v.lightData >> 8) & 0xFFu
    );
    vec2 uv = vec2(light) / 256.0 + vec2(0.5 / 16.0);
    return clamp(uv, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
}
