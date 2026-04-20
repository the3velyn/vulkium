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


layout(set = 1, binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
    return vec4(texture(tex_light, uv).rgb, 1);
}

vec3 computeMultiplier(Vertex V) {
    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    return tint.xyz;
}


Vertex V0;
Vertex Vp;
Vertex V2;
void computeOutputColour(inout vec3 colour) {
    vec3 multiplier = gl_BaryCoordEXT.x*computeMultiplier(V0) + gl_BaryCoordEXT.y*computeMultiplier(Vp) + gl_BaryCoordEXT.z*computeMultiplier(V2);
    colour *= multiplier;
}

#ifdef RENDER_FOG
//2 ways to do it, either use an interpolation, or screenspace reversal, screenspace reversal is better when many many vertices
// however interpolation increases ISBE
void applyFog(inout vec3 colour) {
    colour = mix(colour, fogColour.rgb, fogLerp);
}
#endif


layout(set = 1, binding = 0) uniform sampler2D tex_diffuse;

void main() {
    colour = vec4(1.0, 0.0, 1.0, 1.0);
}
