package me.cortex.vulkium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.vulkium.config.TranslucencySortingLevel;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable runtime configuration. Stored as {@code config/vulkium.json} in the instance
 * config directory. Loaded once at client init; changes require a restart (no hot-reload yet).
 *
 * <p>Values are conservative defaults matching "safe observation mode" — vulkium runs its
 * probes + ingest but does not execute draws yet. Once V7 draws land, flip {@link #drawTerrain}
 * to true by default.
 */
public final class VulkiumConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILENAME = "vulkium.json";

    private static volatile VulkiumConfig INSTANCE;

    // --- tunables -------------------------------------------------------------------------

    /** Force vulkium disabled regardless of feature probe. Useful for side-by-side profiling. */
    public boolean forceDisable = false;

    /** Run the boot-time shader sanity check (compiles every shader via shaderc). */
    public boolean runShaderSanityCheck = true;

    /** Run the boot-time compute smoke test (dispatches + validates i*i output). */
    public boolean runComputeSmokeTest = true;

    /**
     * Whether vulkium should issue mesh-shader draws at AFTER_OPAQUE_TERRAIN. Kept as a field
     * for internal kill-switch use (the draw path sets this to false on unrecoverable GPU
     * errors so the session continues with MC's vanilla terrain). Not exposed in the GUI any
     * more — "Vulkium enabled" is the user-facing master switch.
     */
    public boolean drawTerrain = true;

    /** Build the HZB pyramid each frame from the just-rendered depth (at END_MAIN so it
     *  captures vulkium's own terrain). Only worth enabling together with
     *  {@link #enableHzbRegionCull}. */
    public boolean enableHzb = false;

    /** Sample the HZB each frame to prune occluded regions before mesh tasks dispatch.
     *  Requires {@link #enableHzb}. Implies a 1-frame lag (we sample frame N-1's HZB) —
     *  conservative under camera motion. Default off while the consumer stabilizes; enable
     *  after a clean A/B comparison shows no disappearing-chunk regressions on lookaround. */
    public boolean enableHzbRegionCull = false;

    /** Second-pass refinement of {@link #enableHzbRegionCull}: after the region-level bits
     *  are written, dispatch a per-section compute pass that tests each section's 16³-block
     *  AABB against the HZB and writes {@code sectionVisibility}. The terrain task shader
     *  already ANDs region ∧ section visibility, so this doubles down on culling inside
     *  visible regions. Requires {@link #enableHzbRegionCull}. Extra ~15-30µs GPU and one
     *  extra {@code vkCmdCopyBuffer} of ~1MB per frame for the CPU readback. */
    public boolean enableHzbSectionCull = false;

    /** Sort the opaque dispatch list front-to-back by per-section Manhattan distance so
     *  near sections render before far ones. Lets the GPU's early-Z reject more fragments
     *  from occluded far sections, but scatters {@code regionData} / {@code sectionData}
     *  fetches across adjacent task workgroups and loses the natural spatial locality of
     *  compact-id-within-region emission. Net effect is scene-dependent:
     *  <ul>
     *    <li>Dense overdraw (caves, jungles, cities, looking into walls from outside) —
     *        early-Z wins dominate, measurable gpu.opaqueDraw drop.</li>
     *    <li>Open-air / mostly-visible scenes — cache-miss cost can edge out early-Z
     *        benefit; measured ~+15-20µs gpu.opaqueDraw regression in a flat-terrain
     *        test on RTX 3060 (2026-04-21 11:52 run).</li>
     *  </ul>
     *  Default off because open-air is the more common "always-paid" Minecraft viewpoint;
     *  flip on if your typical scenes lean dense. Costs ~13µs CPU on opaqueList.build. */
    public boolean enableFrontToBackSort = false;

    /** Diagnostic toggle — when false, the CPU-side {@code OpaqueDispatchList} compaction is
     *  bypassed (scene UBO's opaqueDispatchListPtr is written as 0). The task shader then
     *  reverts to its legacy one-workgroup-per-section-slot linear dispatch over
     *  {@code maxRegionIndex × 256} IDs with per-thread sectionEmpty no-ops.
     *
     *  <p>Use to isolate "missing-close-chunks" regressions: if bypassing fixes them, the
     *  bug lives in {@link me.cortex.vulkium.render.OpaqueDispatchList#build} or its
     *  {@link me.cortex.vulkium.render.VisibilityTracker} feed (region-level frustum cull
     *  over-aggressive). If bypassing does NOT fix them, the bug is upstream of dispatch
     *  (ingest / region allocation / section ingest race). Default on; toggle for A/B only. */
    public boolean enableOpaqueDispatchList = true;

    /** Max sections the render thread drains from the ingest queue per frame. Vulkium
     *  suppresses MC's own terrain draw, so any queued-but-not-yet-ingested section is an
     *  invisible chunk. High default (4096) avoids visible gaps on RD change / first join.
     *  Lower it (e.g. 512) for smoother progressive chunk streaming at the cost of a longer
     *  total load window. */
    public int drainPerFrame = 4096;

    /** Upload ring section size in MB. Multiplied by {@link #uploadSectionCount} for total VRAM. */
    public int uploadSectionMb = 16;

    /** Upload ring section count (frames in flight). */
    public int uploadSectionCount = 3;

    /** Terrain arena size in MB. Undersizing causes {@code uploadSectionSplit} to return
     *  {@code SIZE_LIMIT}; existing geometry keeps rendering from its allocated slots but new
     *  chunks never write their section headers, so <i>new chunks stop rendering</i> once
     *  the arena fills (progressive starvation rather than crash). Sizing guidance:
     *  <ul>
     *    <li>RD≤16: 256 MB typically fine.</li>
     *    <li>RD 24-32: 512-1024 MB.</li>
     *    <li>RD 48-64 or dense-biome exploration: 1024-4096 MB.</li>
     *    <li>RD 96+ on a 16 GB GPU: 4-8 GB is reasonable and the allocator handles it.</li>
     *  </ul>
     *  Internal SegmentedManager address is 34-bit quad-granular (~32 GB byte-space ceiling
     *  before {@code int} addr truncation), so the slider's 8192 MB cap leaves 4× headroom
     *  below the truncation boundary. */
    public int terrainArenaMb = 512;

    /** Max regions in the ledger. One region = 8×4×8 sections = 256 sections. */
    public int maxRegions = 1024;

    // --- nvidium-parity tunables (for port parity) ----------------------------------------
    //
    // Field names match nvidium's snake_case JSON keys where possible, but kept camelCase in
    // Java since GSON writes the field name verbatim (vulkium's config file uses camelCase
    // throughout). Direct nvidium config file reuse is not supported — semantics only.

    /**
     * Section keep-distance in chunks. Matches nvidium's {@code region_keep_distance}.
     * <ul>
     *   <li>{@code 32} — vanilla behavior: evict sections only when MC has unloaded the
     *       owning chunk ({@code ClientLevel.hasChunk} returns false). Default.</li>
     *   <li>{@code 256} — keep everything. Memory-unbounded; useful for profiling or when
     *       the arena is oversized.</li>
     *   <li>Any value in {@code (32, 256)} — also evict anything outside a square of that
     *       radius around the camera (stricter than vanilla).</li>
     * </ul>
     * Wired in {@link me.cortex.vulkium.managers.SectionManager#sweepKeepDistance}.
     */
    public int regionKeepDistance = 32;

    /** Matches nvidium's {@code translucency_sorting_level}. Controls the resort path that
     *  was just stabilized on 2026-04-20. Wired in {@code SectionManager.drainResorts} and
     *  {@code Renderer.prepareFrame} (cross-section sort). */
    public TranslucencySortingLevel translucencySortingLevel = TranslucencySortingLevel.QUADS;

    /** Matches nvidium's {@code render_fog}. When false, the scene UBO's fog range is pushed
     *  so far out that the fragment shader's fog factor clamps to 0 — effectively disables
     *  fog for vulkium's terrain pass without touching the shader variant. */
    public boolean renderFog = true;

    /** Master toggle for the render-thread per-phase timer. Disabling skips the two
     *  nanoTime() calls per bracketed phase and the auto-flush log spam. Cost when enabled
     *  is under 0.5% of frame time at 200+ FPS. */
    public boolean enablePerfTracker = true;

    /** How often the PerfTracker flushes its running averages to the log, in milliseconds.
     *  Short intervals (≤500ms) let you separate warmup from steady state inside a 10s run
     *  and make before/after A/B comparisons sharper. Clamped to ≥50ms at apply time. */
    public int perfTrackerFlushMs = 500;

    public static VulkiumConfig get() {
        if (INSTANCE == null) {
            synchronized (VulkiumConfig.class) {
                if (INSTANCE == null) INSTANCE = load();
            }
        }
        return INSTANCE;
    }

    /** Persist current values to {@code config/vulkium.json}. Called by the options screen on close. */
    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
        try {
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
            LOGGER.info("Saved config to {}", path);
        } catch (IOException e) {
            LOGGER.warn("Failed to save config to {}: {}", path, e.getMessage());
        }
    }

    private static VulkiumConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
        VulkiumConfig cfg;
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                cfg = GSON.fromJson(reader, VulkiumConfig.class);
                if (cfg == null) cfg = new VulkiumConfig();
                LOGGER.info("Loaded config from {}", path);
            } catch (Exception e) {
                LOGGER.warn("Failed to read {} — falling back to defaults: {}", path, e.getMessage());
                cfg = new VulkiumConfig();
            }
        } else {
            cfg = new VulkiumConfig();
            try {
                Files.createDirectories(path.getParent());
                try (var writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(cfg, writer);
                }
                LOGGER.info("Wrote default config to {}", path);
            } catch (IOException e) {
                LOGGER.warn("Failed to write default config to {}: {}", path, e.getMessage());
            }
        }
        return cfg;
    }
}
