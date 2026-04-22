// Vanilla-matching fog. Mirrors MC 26.2's assets/minecraft/shaders/include/fog.glsl:
//   total_fog_value(spherical, cylindrical, envStart, envEnd, rdStart, rdEnd) =
//       max(linear(spherical, envStart, envEnd), linear(cylindrical, rdStart, rdEnd))
// Linear is clamped to [0, 1]. The max covers both "you're underwater / in lava / in the
// Nether" (environmental, spherical distance) AND "you're at the edge of render distance"
// (render-distance, cylindrical distance) at the same time.
//
// We skip the smoothstep used earlier — vanilla is a straight linear curve and matching
// it exactly is the point of this rewrite.

float linear_fog_value(float dist, float start, float end) {
    if (dist <= start) return 0.0;
    if (dist >= end) return 1.0;
    return (dist - start) / (end - start);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    // max(horizontal, vertical) matches MC's fog_cylindrical_distance exactly.
    return max(length(pos.xz), abs(pos.y));
}

/** Compute vanilla fog lerp value for a point at {@code pos} (camera-relative block coords).
 *  Caller multiplies by fogColour.a before the mix, matching vanilla's
 *  {@code fogValue * fogColor.a} weighting. */
float computeFogLerp(vec3 pos, float envStart, float envEnd, float rdStart, float rdEnd) {
    float envLerp = linear_fog_value(fog_spherical_distance(pos),  envStart, envEnd);
    float rdLerp  = linear_fog_value(fog_cylindrical_distance(pos), rdStart,  rdEnd);
    return max(envLerp, rdLerp);
}
