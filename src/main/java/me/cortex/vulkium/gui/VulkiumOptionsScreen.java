package me.cortex.vulkium.gui;

import me.cortex.vulkium.VulkiumConfig;
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
            boolOption("Vulkium enabled",
                "Master switch. OFF hands rendering back to MC's vanilla terrain path (takes effect "
                    + "immediately; no restart needed). Default: ON.",
                !cfg.forceDisable, v -> cfg.forceDisable = !v),
            boolOption("HZB occlusion",
                "Build the Hi-Z occlusion buffer each frame. No-op until depth tap lands.",
                cfg.enableHzb, v -> cfg.enableHzb = v));

        this.list.addSmall(
            boolOption("Boot shader check",
                "Compile every shader at startup via shaderc to surface errors early. " + TIP_RESTART.getString(),
                cfg.runShaderSanityCheck, v -> cfg.runShaderSanityCheck = v),
            boolOption("Boot compute test",
                "Run the compute-pipeline smoke test at startup. " + TIP_RESTART.getString(),
                cfg.runComputeSmokeTest, v -> cfg.runComputeSmokeTest = v));

        this.list.addSmall(
            intSlider("Ingest drain / frame",
                "Max sections moved from worker capture → live state per frame. Higher = "
                    + "lower latency on large chunk batches, more render-thread work.",
                16, 1024, cfg.drainPerFrame, v -> cfg.drainPerFrame = v),
            mbSlider("Upload ring size (MB)",
                "Per-section staging-buffer size in MB. Multiplied by ring count for total. " + TIP_RESTART.getString(),
                16, 256, 16, cfg.uploadSectionMb, v -> cfg.uploadSectionMb = v));

        this.list.addSmall(
            intSlider("Upload ring count",
                "Number of staging-buffer sections (frames-in-flight). " + TIP_RESTART.getString(),
                2, 8, cfg.uploadSectionCount, v -> cfg.uploadSectionCount = v),
            mbSlider("Terrain arena (MB)",
                "Total VRAM reserved for terrain vertex data. 128 comfortably covers 16-chunk RD. " + TIP_RESTART.getString(),
                64, 1024, 16, cfg.terrainArenaMb, v -> cfg.terrainArenaMb = v));

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
                "Apply MC-style fog in vulkium's terrain pass. NOT YET WIRED — toggle has no "
                    + "effect at the moment; tracked as placeholder for the fog wire-up step.",
                cfg.renderFog, v -> cfg.renderFog = v));

        this.list.addSmall(
            enumSlider("Translucency sort", TranslucencySortingLevel.class,
                "NONE = no sort (cheapest, visually wrong). SECTIONS = cross-section back-to-front "
                    + "only. QUADS = full (cross-section + POV-driven per-section resort).",
                cfg.translucencySortingLevel,
                v -> cfg.translucencySortingLevel = v),
            boolOption("Temporal coherence",
                "Reuse visibility across frames when camera/chunks didn't move. Inert until the "
                    + "HZB temporal-coherence pass lands.",
                cfg.enableTemporalCoherence, v -> cfg.enableTemporalCoherence = v));

        this.list.addSmall(
            boolOption("Automatic memory",
                "Auto-size the terrain arena from free VRAM at boot. Inert — vulkium currently uses "
                    + "the fixed \"Terrain arena (MB)\" slider above.",
                cfg.automaticMemory, v -> cfg.automaticMemory = v),
            intSlider("Extra render distance",
                "Chunks of additional view distance beyond MC's setting. Wired via "
                    + "Options.getEffectiveRenderDistance mixin — extends MC's depthFar and "
                    + "vulkium's region cull in lockstep. SINGLEPLAYER ONLY: on multiplayer the "
                    + "server still caps how many chunks it sends, so the slider has no visible "
                    + "effect past the server's view-distance setting.",
                0, 96, cfg.extraRd, v -> cfg.extraRd = v));
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

    /** MB slider with stepped increments (e.g. 16 MB). The slider position is compressed into
     *  slot indices 0..N so MC's slider widget snaps exactly to step boundaries — no fractional
     *  MB values leak into the config. Stored value stays in real MB so existing code reading
     *  the field doesn't need to know about the step. */
    private static OptionInstance<Integer> mbSlider(String name, String tooltip,
                                                    int minMb, int maxMb, int stepMb,
                                                    int initialMb,
                                                    java.util.function.Consumer<Integer> setter) {
        final int slots = Math.max(1, (maxMb - minMb) / stepMb);
        final int clampedInitial = Math.max(minMb, Math.min(maxMb, initialMb));
        final int initialSlot = (clampedInitial - minMb) / stepMb;
        return new OptionInstance<>(
            "vulkium.opt." + name,
            OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
            (label, slot) -> Component.literal(name + ": " + (minMb + slot * stepMb) + " MB"),
            new OptionInstance.IntRange(0, slots),
            initialSlot,
            slot -> setter.accept(minMb + slot * stepMb));
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
