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
                !cfg.forceDisable, v -> {
                    cfg.forceDisable = !v;
                    // Full teardown + rebuild on every transition. Without this, flipping
                    // off→on in-session left vulkium's renderer state stale — per the 2026-04-20
                    // memory, FPS would drop to vanilla baseline and not recover until a full
                    // client restart. Tearing down everything and letting the next prepareFrame
                    // re-run ensureInit() matches the boot-path exactly.
                    //
                    // Safe to call synchronously from the click handler: OptionInstance's
                    // onValueUpdate fires on the main/render thread between frames, not mid-draw,
                    // so VK resource teardown doesn't race with recording.
                    try {
                        me.cortex.vulkium.render.Renderer.get().shutdown();
                    } catch (Throwable t) {
                        // Don't block the toggle; log and continue to allChanged.
                        org.slf4j.LoggerFactory.getLogger("vulkium/toggle")
                            .warn("Renderer shutdown on toggle failed", t);
                    }
                    me.cortex.vulkium.managers.SectionManager.get().queueFlushAll();
                    // Force MC to re-mark every section dirty and recompile. When vulkium
                    // re-enables, MC's section compile path re-triggers our capture mixin so
                    // vulkium gets fresh data. When vulkium disables, it re-primes MC's own
                    // uber-buffer draw path with valid state instead of whatever it had while
                    // we were cancelling its renderGroup.
                    net.minecraft.client.Minecraft mc =
                        net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.levelExtractor != null) {
                        mc.levelExtractor.allChanged();
                    }
                }),
            boolOption("HZB occlusion",
                "Build the Hi-Z pyramid at END_MAIN from the finished depth buffer. Required "
                    + "by HZB region-cull; a no-op on its own.",
                cfg.enableHzb, v -> cfg.enableHzb = v));

        this.list.addSmall(
            boolOption("HZB region cull",
                "Sample the previous frame's HZB to skip occluded regions at the task-shader "
                    + "gate. Needs HZB build on. 1-frame lag; conservative under motion.",
                cfg.enableHzbRegionCull, v -> cfg.enableHzbRegionCull = v),
            boolOption("HZB section cull",
                "Per-section HZB refinement on top of region cull. Tests each section's "
                    + "AABB individually, catches occluded sections inside visible regions. "
                    + "Requires HZB region cull; adds ~15-30µs GPU and a 1MB readback copy.",
                cfg.enableHzbSectionCull, v -> cfg.enableHzbSectionCull = v));

        this.list.addSmall(
            boolOption("Front-to-back sort",
                "Sort the opaque dispatch list by per-section distance so near sections "
                    + "render before far ones. Lets the GPU's early-Z reject more occluded "
                    + "fragments. ~20µs CPU overhead; pays back via less GPU overdraw.",
                cfg.enableFrontToBackSort, v -> cfg.enableFrontToBackSort = v));

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
                    + "fewer invisible-chunk frames on RD change / first join. Lower = "
                    + "smoother progressive streaming at the cost of a longer total load.",
                64, 8192, cfg.drainPerFrame, v -> cfg.drainPerFrame = v),
            mbSlider("Upload ring size (MB)",
                "Per-section staging-buffer size in MB. Multiplied by ring count for total. " + TIP_RESTART.getString(),
                16, 256, 16, cfg.uploadSectionMb, v -> cfg.uploadSectionMb = v));

        this.list.addSmall(
            intSlider("Upload ring count",
                "Number of staging-buffer sections (frames-in-flight). " + TIP_RESTART.getString(),
                2, 8, cfg.uploadSectionCount, v -> cfg.uploadSectionCount = v),
            mbSlider("Terrain arena (MB)",
                "Total VRAM reserved for terrain vertex data. Undersizing starves new chunks. "
                    + "Guide: 256 for RD≤16, 1024 for RD=32, 2048+ for RD>48 or dense biomes. "
                    + TIP_RESTART.getString(),
                64, 8192, 64, cfg.terrainArenaMb, v -> cfg.terrainArenaMb = v));

        this.list.addSmall(
            intSlider("Max regions",
                "Region-ledger capacity. One region = 8×4×8 sections (2048 sections = 8 MB of "
                    + "section metadata per 256 regions, 16-byte region metadata on top). "
                    + "Raise when loading many areas at high keep-distance; overflow drops "
                    + "new captures until a region evicts. " + TIP_RESTART.getString(),
                256, 32768, cfg.maxRegions, v -> cfg.maxRegions = v));

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
            boolOption("Perf tracker",
                "Per-phase CPU timers flushed to the log. ~0.5% frame-time overhead at 200+ FPS; "
                    + "disable for zero overhead on release builds.",
                cfg.enablePerfTracker, v -> {
                    cfg.enablePerfTracker = v;
                    me.cortex.vulkium.diag.PerfTracker.setEnabled(v);
                }));

        this.list.addSmall(
            intSliderLabel("Perf flush",
                "How often PerfTracker dumps its averages to the log. Short = sharper A/B "
                    + "comparisons; long = less log volume.",
                50, 5000, cfg.perfTrackerFlushMs,
                v -> v >= 1000 ? (v / 1000) + "s" : v + "ms",
                v -> {
                    cfg.perfTrackerFlushMs = v;
                    me.cortex.vulkium.diag.PerfTracker.setFlushIntervalNs((long) v * 1_000_000L);
                }));
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
