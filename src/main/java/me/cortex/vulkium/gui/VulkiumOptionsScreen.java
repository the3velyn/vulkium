package me.cortex.vulkium.gui;

import me.cortex.vulkium.VulkiumConfig;
import me.cortex.vulkium.config.StatisticsLoggingLevel;
import me.cortex.vulkium.config.TranslucencySortingLevel;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Vulkium's own options sub-screen, styled to match MC 26.2's Video Settings layout. Reached
 * via the "Vulkium Options..." button that {@code VideoSettingsScreenMixin} appends to the
 * standard video settings screen.
 *
 * <p>Each config field is exposed as an {@link OptionInstance} so MC's own widget pipeline
 * renders sliders/toggles consistently with vanilla settings. Persistence happens on
 * {@link #onClose()} via {@link VulkiumConfig#save()}.
 *
 * <p>Changes that require a restart (arena sizes, upload ring, max-regions) are annotated in
 * their tooltips so users don't wonder why the slider moved but the memory footprint didn't.
 */
public final class VulkiumOptionsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.literal("Vulkium Options");

    private static final Component TIP_RESTART = Component.literal("Requires restart.");

    public VulkiumOptionsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
    }

    @Override
    protected void addOptions() {
        VulkiumConfig cfg = VulkiumConfig.get();

        this.list.addSmall(
            boolOption("Force disable",
                "Force vulkium disabled regardless of GPU/feature probe. Takes effect immediately — no restart needed.",
                cfg.forceDisable, v -> cfg.forceDisable = v),
            boolOption("Draw terrain",
                "Run vulkium's mesh-shader terrain draws. Off = vulkium observes + ingests only.",
                cfg.drawTerrain, v -> cfg.drawTerrain = v));

        this.list.addSmall(
            boolOption("HZB occlusion",
                "Build the Hi-Z occlusion buffer each frame. No-op until depth tap lands.",
                cfg.enableHzb, v -> cfg.enableHzb = v),
            boolOption("Boot shader check",
                "Compile every shader at startup via shaderc to surface errors early. " + TIP_RESTART.getString(),
                cfg.runShaderSanityCheck, v -> cfg.runShaderSanityCheck = v));

        this.list.addSmall(
            boolOption("Boot compute test",
                "Run the compute-pipeline smoke test at startup. " + TIP_RESTART.getString(),
                cfg.runComputeSmokeTest, v -> cfg.runComputeSmokeTest = v));

        this.list.addSmall(
            intSlider("Ingest drain / frame",
                "Max sections moved from worker capture → live state per frame. Higher = "
                    + "lower latency on large chunk batches, more render-thread work.",
                16, 1024, cfg.drainPerFrame, v -> cfg.drainPerFrame = v),
            intSlider("Upload ring size (MB)",
                "Per-section staging-buffer size in MB. Multiplied by ring count for total. " + TIP_RESTART.getString(),
                4, 256, cfg.uploadSectionMb, v -> cfg.uploadSectionMb = v));

        this.list.addSmall(
            intSlider("Upload ring count",
                "Number of staging-buffer sections (frames-in-flight). " + TIP_RESTART.getString(),
                2, 8, cfg.uploadSectionCount, v -> cfg.uploadSectionCount = v),
            intSlider("Terrain arena (MB)",
                "Total VRAM reserved for terrain vertex data. 128 comfortably covers 16-chunk RD. " + TIP_RESTART.getString(),
                64, 1024, cfg.terrainArenaMb, v -> cfg.terrainArenaMb = v));

        this.list.addSmall(
            intSlider("Max regions",
                "Region-ledger capacity. One region = 8×4×8 sections. " + TIP_RESTART.getString(),
                256, 4096, cfg.maxRegions, v -> cfg.maxRegions = v));

        // --- nvidium-parity options --------------------------------------------------------
        // Sliders here mirror nvidium's settings page so users coming from nvidium find the
        // same knobs. Sliders that toggle an enum render the enum name in the label (e.g.
        // "Translucency sort: QUADS") so the slider value is readable.

        this.list.addSmall(
            intSliderLabel("Region keep distance",
                "Chunks to keep loaded around the camera. 32 = Vanilla (MC-driven unload). "
                    + "256 = Keep All (never evict). Intermediate values sweep sections outside "
                    + "(value+4) chunks every 60 frames.",
                32, 256, cfg.regionKeepDistance,
                v -> v == 32 ? "Vanilla" : (v == 256 ? "Keep All" : v + " chunks"),
                v -> cfg.regionKeepDistance = v),
            boolOption("Render fog",
                "Apply MC-style fog in vulkium's terrain pass. Matches nvidium's render_fog.",
                cfg.renderFog, v -> cfg.renderFog = v));

        this.list.addSmall(
            enumSlider("Translucency sort", TranslucencySortingLevel.class,
                "NONE = no sort (cheapest, visually wrong). SECTIONS = cross-section back-to-front "
                    + "only. QUADS = full (cross-section + POV-driven per-section resort).",
                cfg.translucencySortingLevel,
                v -> cfg.translucencySortingLevel = v),
            enumSlider("Statistics level", StatisticsLoggingLevel.class,
                "Granularity of periodic per-frame counter logging. Tracked but currently inert — "
                    + "F3 overlay integration lands with V9.",
                cfg.statisticsLevel,
                v -> cfg.statisticsLevel = v));

        this.list.addSmall(
            boolOption("Temporal coherence",
                "Reuse visibility across frames when camera/chunks didn't move. Inert until the "
                    + "HZB temporal-coherence pass lands.",
                cfg.enableTemporalCoherence, v -> cfg.enableTemporalCoherence = v),
            boolOption("Async BFS",
                "Run section-graph BFS on a worker thread. Inert — vulkium's visibility is GPU-driven "
                    + "via task-shader culling, not CPU BFS.",
                cfg.asyncBfs, v -> cfg.asyncBfs = v));

        this.list.addSmall(
            boolOption("Automatic memory",
                "Auto-size the terrain arena from free VRAM at boot. Inert — vulkium currently uses "
                    + "the fixed \"Terrain arena (MB)\" slider above.",
                cfg.automaticMemory, v -> cfg.automaticMemory = v),
            intSlider("Extra render distance",
                "Chunks of additional view distance beyond MC's setting. Inert until the "
                    + "GameRenderer.getRenderDistance mixin lands.",
                0, 64, cfg.extraRd, v -> cfg.extraRd = v));
    }

    @Override
    public void onClose() {
        VulkiumConfig.get().save();
        super.onClose();
    }

    private static OptionInstance<Boolean> boolOption(String name, String tooltip,
                                                      boolean initial,
                                                      java.util.function.Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(
            "vulkium.opt." + name,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            initial,
            setter::accept);
    }

    private static OptionInstance<Integer> intSlider(String name, String tooltip,
                                                     int min, int max, int initial,
                                                     java.util.function.Consumer<Integer> setter) {
        return new OptionInstance<>(
            "vulkium.opt." + name,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            (label, value) -> Component.literal(name + ": " + value),
            new OptionInstance.IntRange(min, max),
            initial,
            setter::accept);
    }

    /** Int slider with a custom label-from-value formatter (e.g. 32→"Vanilla", 256→"Keep All"). */
    private static OptionInstance<Integer> intSliderLabel(String name, String tooltip,
                                                          int min, int max, int initial,
                                                          java.util.function.IntFunction<String> fmt,
                                                          java.util.function.Consumer<Integer> setter) {
        return new OptionInstance<>(
            "vulkium.opt." + name,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            (label, value) -> Component.literal(name + ": " + fmt.apply(value)),
            new OptionInstance.IntRange(min, max),
            initial,
            setter::accept);
    }

    /** Renders an enum as a slider whose label shows the enum constant name. Range 0..ordinal(max)
     *  so MC's slider widget handles click-through-values cleanly. */
    private static <T extends Enum<T>> OptionInstance<Integer> enumSlider(
            String name, Class<T> cls, String tooltip, T initial,
            java.util.function.Consumer<T> setter) {
        T[] values = cls.getEnumConstants();
        return new OptionInstance<>(
            "vulkium.opt." + name,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            (label, v) -> Component.literal(name + ": " + values[Math.floorMod(v, values.length)].name()),
            new OptionInstance.IntRange(0, values.length - 1),
            initial.ordinal(),
            v -> setter.accept(values[Math.floorMod(v, values.length)]));
    }

    /** Used by the Video Settings mixin to construct us without importing from a mixin package. */
    public static Screen open(Screen parent, Options options) {
        return new VulkiumOptionsScreen(parent, options);
    }
}
