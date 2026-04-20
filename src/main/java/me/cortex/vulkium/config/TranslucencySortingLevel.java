package me.cortex.vulkium.config;

/** Mirrors nvidium's TranslucencySortingLevel for port parity.
 *  <ul>
 *    <li>{@link #NONE} — no translucent sort. Quads render in mesh-build order; cross-section
 *        order is arbitrary. Cheapest; visually wrong for overlapping translucent quads.</li>
 *    <li>{@link #SECTIONS} — cross-section back-to-front sort only. Per-section quad order
 *        stays at MC's build-time sort (no POV resort capture).</li>
 *    <li>{@link #QUADS} — full parity with vanilla MC. Cross-section + per-section POV-driven
 *        resort (quads re-permuted whenever the camera crosses a sub-chunk boundary).</li>
 *  </ul>
 */
public enum TranslucencySortingLevel {
    NONE,
    SECTIONS,
    QUADS
}
