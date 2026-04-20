package me.cortex.vulkium.gui;

import me.cortex.vulkium.VulkiumConfig;
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

    /** Used by the Video Settings mixin to construct us without importing from a mixin package. */
    public static Screen open(Screen parent, Options options) {
        return new VulkiumOptionsScreen(parent, options);
    }
}
