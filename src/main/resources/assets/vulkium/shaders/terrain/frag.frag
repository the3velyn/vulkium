#version 460
#define UNROLL_LOOP

#extension GL_EXT_fragment_shader_barycentric : require


#include <vulkium:occlusion/scene.glsl>
#include <vulkium:terrain/vertex_format.glsl>




layout(location = 0) out vec4 colour;
#if defined(RENDER_FOG) || defined(TRANSLUCENT_PASS)
layout(location = 1) in Interpolants {
    #ifdef RENDER_FOG
    float fogLerp;
    #endif
    #ifdef TRANSLUCENT_PASS
    vec2 uv;
    vec3 v_colour;
    #endif
};
#endif


layout(set = 0, binding = 2) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    return vec4(texture(tex_light, uv).rgb, 1);
}

vec4 computeMultiplier(Vertex V) {
    vec4 tint = decodeVertexColour(V);
    tint.rgb *= sampleLight(decodeLightUV(V)).rgb;
    return tint; // rgb = lit tint, a = vertex alpha (propagated for translucent blending)
}


Vertex V0;
Vertex Vp;
Vertex V2;
/** Combined color + alpha multiplier: out.rgb = lit tint (texture-color scalar),
 *  out.a = interpolated vertex alpha. Matches vanilla terrain's `sample * vertexColor`. */
vec4 interpolateMultiplier() {
    return gl_BaryCoordEXT.x*computeMultiplier(V0)
         + gl_BaryCoordEXT.y*computeMultiplier(Vp)
         + gl_BaryCoordEXT.z*computeMultiplier(V2);
}

#ifdef RENDER_FOG
//2 ways to do it, either use an interpolation, or screenspace reversal, screenspace reversal is better when many many vertices
// however interpolation increases ISBE
void applyFog(inout vec3 colour) {
    colour = mix(colour, fogColour.rgb, fogLerp);
}
#endif


layout(set = 0, binding = 1) uniform sampler2D tex_diffuse;

void main() {
    uint quadId = uint(gl_PrimitiveID) >> 4;
    uint triSel = (uint(gl_PrimitiveID) >> 3) & 1u;
    Vertex Vq0 = terrainData.data[(quadId<<2)+0];
    Vertex Vq2 = terrainData.data[(quadId<<2)+2];
    // Mesh shader emits triangles as:
    //   triSel=0: (V0, V1, V2) → bary (.x, .y, .z) maps to (V0, V1, V2)
    //   triSel=1: (V2, V3, V0) → bary (.x, .y, .z) maps to (V2, V3, V0)
    // Assign V0/Vp/V2 globals for computeOutputColour(), matching the emitted tri's order:
    //   triSel=0: tri vertices = (V0, V1, V2)
    //   triSel=1: tri vertices = (V2, V3, V0)
    vec2 sampleUv;
    if (triSel == 0u) {
        Vertex V1 = terrainData.data[(quadId<<2)+1];
        V0 = Vq0; Vp = V1; V2 = Vq2;
        sampleUv = gl_BaryCoordEXT.x * decodeVertexUV(Vq0)
                 + gl_BaryCoordEXT.y * decodeVertexUV(V1)
                 + gl_BaryCoordEXT.z * decodeVertexUV(Vq2);
    } else {
        Vertex V3 = terrainData.data[(quadId<<2)+3];
        V0 = Vq2; Vp = V3; V2 = Vq0;
        sampleUv = gl_BaryCoordEXT.x * decodeVertexUV(Vq2)
                 + gl_BaryCoordEXT.y * decodeVertexUV(V3)
                 + gl_BaryCoordEXT.z * decodeVertexUV(Vq0);
    }
    vec4 albedo = texture(tex_diffuse, sampleUv);
    uint alphaCutoffIdx = uint(gl_PrimitiveID) & 3u;
    float cut = (alphaCutoffIdx == 1u) ? 0.1 : ((alphaCutoffIdx == 2u) ? 0.5 : 0.0);
    // Alpha cutoff + mipmaps: at coarse LODs, mipped alpha averages texel neighbours, so
    // pixels that are empty at mip 0 (transparent gaps between grass blades / kelp fronds)
    // inherit neighbouring-texel alpha and cross the cutoff — distant tall-grass textures
    // look like opaque rectangles. Test the cutoff against the SHARP (mip-0) alpha so the
    // silhouette stays clean, while RGB still uses the mipped sample for clean distant
    // colour. Only do the extra fetch for cutout (cut>0); opaque rarely discards anyway.
    if (cut > 0.0) {
        float sharpAlpha = textureLod(tex_diffuse, sampleUv, 0.0).a;
        if (sharpAlpha <= cut) discard;
    } else if (albedo.a <= cut) {
        discard;
    }

    vec4 multiplier = interpolateMultiplier();
    // vanilla rendertype_translucent: fragColor = texSample * vertexColor (rgb AND a).
    colour = vec4(albedo.rgb * multiplier.rgb, albedo.a * multiplier.a);
}
