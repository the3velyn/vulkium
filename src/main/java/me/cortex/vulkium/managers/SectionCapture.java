package me.cortex.vulkium.managers;

/**
 * Legacy entry point for MC-vanilla chunk ingestion. On the sodium edition this is reduced
 * to a sentinel + zero-valued metric stubs; Sodium owns chunk compilation entirely via
 * {@code ChunkBuilderMeshingTaskMixin} → {@code SectionManager.offerFromSodium}, so MC's
 * {@code SectionCompiler} never fires and the worker-thread capture code that used to
 * live here would have nothing to capture.
 *
 * <p>Kept as a thin facade so:
 * <ul>
 *   <li>{@link #UNKNOWN_SECTION} stays a stable constant for the few SectionManager
 *       call sites that still guard against the pre-Sodium "no compile in flight"
 *       sentinel (defensive — the sentinel can never appear on this branch, but
 *       leaving the checks intact keeps the standalone-edition diff smaller).</li>
 *   <li>{@link #sectionsCaptured()} / {@link #vertexBytesTotal()} / {@link #indexBytesTotal()}
 *       remain callable from {@code VulkiumKeys} + {@code VulkiumHudOverlay} without
 *       cascading those files into rewrites. They return 0 forever on the sodium
 *       branch — an accurate signal that MC-side capture is silent.</li>
 * </ul>
 *
 * Deleted in this round: {@code beginSectionCompile} / {@code endSectionCompile} /
 * {@code currentCompilingSectionKey} / {@code onSectionMeshCompiled} — all dead with the
 * removal of {@code SectionCompilerMixin} + {@code CompiledSectionMeshMixin}.
 */
public final class SectionCapture {

    private SectionCapture() {}

    /** Sentinel returned when no MC-side compile is in flight. On the sodium branch this
     *  can never appear (no MC-side compile path runs at all), but a handful of
     *  SectionManager call sites guard against it defensively. */
    public static final long UNKNOWN_SECTION = Long.MIN_VALUE;

    // --- diagnostics — return 0 forever under sodium ------------------------
    public static long sectionsCaptured() { return 0L; }
    public static long vertexBytesTotal() { return 0L; }
    public static long indexBytesTotal()  { return 0L; }
}
