package me.cortex.vulkium.gui;

import me.cortex.vulkium.VulkiumConfig;
import me.cortex.vulkium.config.TranslucencySortingLevel;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Vulkium's Sodium-config-API entrypoint. Registered via the {@code sodium:config_api_user}
 * entrypoint in {@code fabric.mod.json}. Sodium constructs one instance and calls
 * {@link #registerConfigLate} after the game launches; we register vulkium's options pages
 * which then appear in Sodium's video-settings screen sidebar alongside Sodium's own.
 *
 * <p>This replaces the deprecated standalone {@code VulkiumOptionsScreen} + the two
 * {@code VideoSettingsScreenMixin} ("Vulkium Options..." button) mixins. Native styling,
 * proper option types, dynamic dependency tracking — all via Sodium's UI.
 *
 * <p>Restart semantics: most vulkium tunables apply at next vulkium-init (e.g. arena size,
 * region count). Toggles read per-frame (HZB, fog, draw enable) apply live. Per-option
 * tooltips note when a restart is needed.
 */
public class VulkiumConfigEntryPoint implements ConfigEntryPoint {
    private static final String NS = "vulkium";

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NS, path);
    }

    private final VulkiumConfig cfg = VulkiumConfig.get();
    private final StorageEventHandler flush = cfg::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .addPage(buildGeneralPage(builder))
            .addPage(buildCullingPage(builder))
            .addPage(buildMemoryPage(builder));
    }

    private OptionPageBuilder buildGeneralPage(ConfigBuilder b) {
        var page = b.createOptionPage().setName(Component.literal("General"));

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createBooleanOption(id("general.enabled"))
                .setName(Component.literal("Vulkium Enabled"))
                .setTooltip(Component.literal(
                    "Master toggle for vulkium's mesh-shader terrain pipeline. " +
                    "When off, Sodium handles terrain rendering. Triggers a chunk " +
                    "reload on apply so the inactive renderer can rebuild its GPU " +
                    "data — brief loading hitch, then full handoff."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.forceDisable = !v, () -> !cfg.forceDisable)
                .setDefaultValue(true)
                .setImpact(OptionImpact.HIGH)
                // REQUIRES_RENDERER_RELOAD triggers MC's LevelRenderer.allChanged on apply.
                // That flushes both Sodium's render-state and vulkium's live table, then
                // streams everything back in — whichever renderer is now active uploads
                // fresh GPU data. Without this, toggling vulkium off would leave Sodium
                // with no uploaded geometry (because vulkium had been cancelling Sodium's
                // uploadResults) and terrain would stay blank until manual F3+A.
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
            )
            .addOption(b.createBooleanOption(id("general.render_fog"))
                .setName(Component.literal("Render Fog"))
                .setTooltip(Component.literal(
                    "Apply vanilla MC fog (env + render-distance) to vulkium's terrain. " +
                    "When off, terrain extends to the far plane without fade. " +
                    "Applies on next frame."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.renderFog = v, () -> cfg.renderFog)
                .setDefaultValue(true)
                .setImpact(OptionImpact.LOW)
            )
        );

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createEnumOption(id("general.translucency_sort"), TranslucencySortingLevel.class)
                .setName(Component.literal("Translucency Sort"))
                .setTooltip(Component.literal(
                    "Quality of translucent (water, glass) ordering. " +
                    "QUADS = vanilla per-quad sort, fixes overlapping water/glass; " +
                    "SECTIONS = per-section sort, cheaper but glass overlap visible; " +
                    "NONE = no sorting (fastest, worst quality)."))
                .setElementNameProvider(e -> Component.literal(e.name()))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.translucencySortingLevel = v,
                            () -> cfg.translucencySortingLevel)
                .setDefaultValue(TranslucencySortingLevel.QUADS)
                .setImpact(OptionImpact.LOW)
            )
        );

        return page;
    }

    private OptionPageBuilder buildCullingPage(ConfigBuilder b) {
        var page = b.createOptionPage().setName(Component.literal("Culling"));

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createBooleanOption(id("culling.hzb_enabled"))
                .setName(Component.literal("Hierarchical Z-Buffer"))
                .setTooltip(Component.literal(
                    "Build the HZB mipmap pyramid from each frame's depth buffer. " +
                    "Prerequisite for region/section culling. Costs ~0.2ms GPU per frame."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.enableHzb = v, () -> cfg.enableHzb)
                .setDefaultValue(false)
                .setImpact(OptionImpact.LOW)
            )
            .addOption(b.createBooleanOption(id("culling.hzb_region"))
                .setName(Component.literal("Region Cull (HZB)"))
                .setTooltip(Component.literal(
                    "Sample the HZB to skip occluded REGIONS (256-section groups) before " +
                    "task dispatch. Requires HZB enabled. 1-frame lag — conservative under " +
                    "fast camera motion."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.enableHzbRegionCull = v, () -> cfg.enableHzbRegionCull)
                .setDefaultValue(false)
                .setImpact(OptionImpact.MEDIUM)
            )
            .addOption(b.createBooleanOption(id("culling.hzb_section"))
                .setName(Component.literal("Section Cull (HZB)"))
                .setTooltip(Component.literal(
                    "Refine region culling per-section (16³ AABBs vs HZB). " +
                    "Requires Region Cull. Extra ~15-30µs GPU per frame."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.enableHzbSectionCull = v, () -> cfg.enableHzbSectionCull)
                .setDefaultValue(false)
                .setImpact(OptionImpact.MEDIUM)
            )
            .addOption(b.createBooleanOption(id("culling.front_to_back"))
                .setName(Component.literal("Front-to-Back Sort"))
                .setTooltip(Component.literal(
                    "Sort opaque dispatch list near-to-far so early-Z rejects more occluded " +
                    "fragments. Helps dense scenes (caves, cities); can regress open-air. " +
                    "Test both settings on your typical viewpoint."))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.enableFrontToBackSort = v, () -> cfg.enableFrontToBackSort)
                .setDefaultValue(false)
                .setImpact(OptionImpact.LOW)
            )
        );

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createIntegerOption(id("culling.keep_distance"))
                .setName(Component.literal("Section Keep Distance"))
                .setTooltip(Component.literal(
                    "Chunk radius to keep sections live. 32 = vanilla behaviour (evict when " +
                    "MC unloads chunk). 256 = keep all (memory-unbounded). Intermediate " +
                    "values evict outside that square radius around the camera."))
                .setValueFormatter(v -> v >= 256
                    ? Component.literal("Keep max")
                    : Component.literal(v + " chunks"))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.regionKeepDistance = v, () -> cfg.regionKeepDistance)
                .setRange(32, 256, 16)
                .setDefaultValue(32)
                .setImpact(OptionImpact.LOW)
            )
        );

        return page;
    }

    private OptionPageBuilder buildMemoryPage(ConfigBuilder b) {
        var page = b.createOptionPage().setName(Component.literal("Memory"));

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createIntegerOption(id("memory.terrain_arena_mb"))
                .setName(Component.literal("Terrain Arena (MB)"))
                .setTooltip(Component.literal(
                    "GPU memory reserved for terrain geometry. Pre-allocated at vulkium " +
                    "init. Undersizing makes new chunks fail to upload (silent — they stop " +
                    "rendering). RD≤16: 256MB. RD 24-32: 512-1024. RD 48+: 2048-4096. " +
                    "Requires restart."))
                .setValueFormatter(v -> Component.literal(v + " MB"))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.terrainArenaMb = v, () -> cfg.terrainArenaMb)
                .setRange(64, 8192, 64)
                .setDefaultValue(512)
                .setImpact(OptionImpact.HIGH)
            )
            .addOption(b.createIntegerOption(id("memory.max_regions"))
                .setName(Component.literal("Max Regions"))
                .setTooltip(Component.literal(
                    "Ledger capacity for chunk regions (1 region = 256 sections). " +
                    "Overflow makes new regions drop. RD≤32: 1024 fine. RD 48+: 2048+. " +
                    "Requires restart."))
                .setValueFormatter(v -> Component.literal(String.valueOf(v)))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.maxRegions = v, () -> cfg.maxRegions)
                .setRange(256, 4096, 256)
                .setDefaultValue(1024)
                .setImpact(OptionImpact.MEDIUM)
            )
        );

        page.addOptionGroup(b.createOptionGroup()
            .addOption(b.createIntegerOption(id("memory.drain_per_frame"))
                .setName(Component.literal("Drain Per Frame"))
                .setTooltip(Component.literal(
                    "Max chunk-build outputs ingested per frame. Higher = faster initial " +
                    "load + after-F3+A recovery, but bigger CPU spikes. Applies live."))
                .setValueFormatter(v -> Component.literal(String.valueOf(v)))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.drainPerFrame = v, () -> cfg.drainPerFrame)
                .setRange(64, 8192, 64)
                .setDefaultValue(4096)
                .setImpact(OptionImpact.LOW)
            )
            .addOption(b.createIntegerOption(id("memory.upload_section_mb"))
                .setName(Component.literal("Upload Section (MB)"))
                .setTooltip(Component.literal(
                    "Size of one upload-stream ring section. Total ring = this × Frames In " +
                    "Flight. Smaller = lower VRAM but more upload-fence stalls under load. " +
                    "Requires restart."))
                .setValueFormatter(v -> Component.literal(v + " MB"))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.uploadSectionMb = v, () -> cfg.uploadSectionMb)
                .setRange(4, 64, 4)
                .setDefaultValue(16)
                .setImpact(OptionImpact.LOW)
            )
            .addOption(b.createIntegerOption(id("memory.upload_section_count"))
                .setName(Component.literal("Frames In Flight"))
                .setTooltip(Component.literal(
                    "Number of upload-stream ring sections. Must match or exceed Mojang's " +
                    "MAX_SUBMITS_IN_FLIGHT (currently 3). Requires restart."))
                .setValueFormatter(v -> Component.literal(String.valueOf(v)))
                .setStorageHandler(flush)
                .setBinding(v -> cfg.uploadSectionCount = v, () -> cfg.uploadSectionCount)
                .setRange(1, 6, 1)
                .setDefaultValue(3)
                .setImpact(OptionImpact.LOW)
            )
        );

        return page;
    }
}
