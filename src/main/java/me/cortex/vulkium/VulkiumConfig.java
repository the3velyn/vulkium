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

    /** Whether HzbBuilder + MojangDepthTap run each frame. No-op until Mojang depth tap lands. */
    /** HZB pyramid build runs each frame at AFTER_OPAQUE_TERRAIN. Currently no consumer —
     *  pyramid is built but never read. Turning on costs ~12 compute dispatches/frame for
     *  no gain; flip to {@code true} once the HZB region-cull compute pass is wired (the
     *  feature that actually cuts task-shader work on occluded geometry). */
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
