package me.cortex.vulkium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.vulkium.config.StatisticsLoggingLevel;
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

    /** Whether HzbBuilder + MojangDepthTap run each frame. No-op until Mojang depth tap lands. */
    public boolean enableHzb = false;

    /** Max sections the render thread drains from the ingest queue per frame. */
    public int drainPerFrame = 256;

    /** Upload ring section size in MB. Multiplied by {@link #uploadSectionCount} for total VRAM. */
    public int uploadSectionMb = 16;

    /** Upload ring section count (frames in flight). */
    public int uploadSectionCount = 3;

    /** Terrain arena size in MB. Sized for 32-chunk RD with per-block-edit churn headroom;
     *  undersizing causes section ingests to hit SIZE_LIMIT and leave stale GPU headers. */
    public int terrainArenaMb = 256;

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
     *   <li>{@code 32} — vanilla behavior: evict out-of-RD sections as MC unloads them.</li>
     *   <li>{@code 256} — keep everything. Matches vulkium's current (no-eviction) baseline
     *       — safe default while the arena is sized for the worst case.</li>
     *   <li>Any value in {@code (32, 256)} — keep sections within a square of that radius
     *       around the camera; evict further ones on a periodic sweep.</li>
     * </ul>
     * Wired in {@link me.cortex.vulkium.managers.SectionManager#sweepKeepDistance}.
     */
    public int regionKeepDistance = 256;

    /** Matches nvidium's {@code translucency_sorting_level}. Controls the resort path that
     *  was just stabilized on 2026-04-20. Wired in {@code SectionManager.drainResorts} and
     *  {@code Renderer.prepareFrame} (cross-section sort). */
    public TranslucencySortingLevel translucencySortingLevel = TranslucencySortingLevel.QUADS;

    /** Matches nvidium's {@code render_fog}. When false, the scene UBO's fog range is pushed
     *  so far out that the fragment shader's fog factor clamps to 0 — effectively disables
     *  fog for vulkium's terrain pass without touching the shader variant. */
    public boolean renderFog = true;

    /** Matches nvidium's {@code statistics_level}. Tracked but currently inert — F3 overlay
     *  integration lands with V9. */
    public StatisticsLoggingLevel statisticsLevel = StatisticsLoggingLevel.NONE;

    /** Matches nvidium's {@code enable_temporal_coherence}. Inert until HZB temporal-coherence
     *  pass lands (nvidium's 6-phase pipeline step 4 — vulkium currently runs phases 1-3). */
    public boolean enableTemporalCoherence = false;

    /** Matches nvidium's {@code async_bfs}. Inert — vulkium's visibility is GPU-driven via
     *  task-shader culling, not a CPU BFS, so there's no async vs. sync tradeoff to expose. */
    public boolean asyncBfs = false;

    /** Matches nvidium's {@code automatic_memory}. Inert — vulkium currently uses a fixed
     *  {@link #terrainArenaMb}. Auto-sizing based on {@code vmaGetHeapBudgets} is a follow-up. */
    public boolean automaticMemory = false;

    /** Matches nvidium's {@code max_geometry_memory}. Alias of {@link #terrainArenaMb} —
     *  kept for config-file parity with nvidium users; the runtime reads {@link #terrainArenaMb}.
     *  If you edit this value, also edit {@link #terrainArenaMb}. */
    public int maxGeometryMemory = 256;

    /** Legacy — used to hold nvidium's {@code extra_rd}. Vulkium now raises MC's own RD slider
     *  max to 128 via {@code OptionsRenderDistanceMixin}, so users set render distance in the
     *  vanilla slider directly. Field kept (defaults to 0 and reads nothing) purely so existing
     *  config files don't blow up with GSON complaints. Safe to delete from {@code vulkium.json}. */
    @Deprecated
    public int extraRd = 0;

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
