package me.cortex.vulkium.config;

/** Mirrors nvidium's StatisticsLoggingLevel for port parity. Level controls what granularity
 *  of per-frame counters is periodically logged / surfaced to the F3 overlay. Higher levels
 *  imply more GPU-side counter traffic (shader-written stats buffer readbacks).
 *  Currently inert — tracked for future wire-up into vulkium's diagnostic path. */
public enum StatisticsLoggingLevel {
    NONE,
    FRUSTUM,
    REGIONS,
    SECTIONS,
    QUADS
}
