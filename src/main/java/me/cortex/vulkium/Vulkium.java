package me.cortex.vulkium;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import me.cortex.vulkium.blaze3d.VulkanDetect;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionManager;
import me.cortex.vulkium.vk.ShaderSanityCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vulkium client entry point. Runs at Fabric client init.
 *
 * <p>Vulkium is a standalone mesh-shader-accelerated terrain renderer for MC 26.2+ on Mojang's
 * native Vulkan backend. It does not depend on Sodium. Rendering acceleration is hard-gated on
 * Turing+ hardware (requires {@code VK_EXT_mesh_shader}); on GPUs without it, vulkium disables
 * itself and vanilla MC renders terrain normally.
 */
public final class Vulkium implements ClientModInitializer {
    public static final String MOD_ID = "vulkium";
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium");

    /**
     * Set after the Mojang renderer has been initialized and we've determined whether we
     * actually have a Vulkan backend + the required feature set. Read by every other subsystem
     * to gate work on "vulkium is actually running."
     */
    private static volatile boolean enabled;

    /** The feature probe result captured once at startup. */
    private static volatile VulkanDetect.ProbeResult probe;

    /** Render-thread-owned region ledger. Created at CLIENT_STARTED, closed at CLIENT_STOPPING. */
    private static volatile RegionManager regionManager;

    /** Upper bound on concurrent regions. One region = 8×4×8 sections. */
    private static final int MAX_REGIONS = 1024;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Vulkium {} loaded (MC {}). Initialization deferred until Mojang's renderer is ready.",
            getVersion(), Minecraft.getInstance() == null ? "<uninit>" : "initialized");

        // We can't feature-probe at onInitializeClient() because RenderSystem.getDevice() isn't
        // yet populated — Mojang's backend is created later during client boot. Defer to a
        // lifecycle event (CLIENT_STARTED, or we hook later via a mixin into
        // com.mojang.blaze3d.systems.RenderSystem.initRenderer).
        ClientLifecycleEvents.CLIENT_STARTED.register(Vulkium::onClientStarted);
        ClientLifecycleEvents.CLIENT_STOPPING.register(Vulkium::onClientStopping);
    }

    private static void onClientStarted(Minecraft client) {
        probe = VulkanDetect.probe();
        if (probe.meetsVulkiumGate()) {
            enabled = true;
            LOGGER.info("Vulkium ENABLED. {}", probe);
            MojangVulkanBridge.logBackendInfo();
            // Compile every shader once up-front. A translation error or missing include
            // surfaces as a log line here instead of a first-draw crash deep in V7.
            try {
                ShaderSanityCheck.runAll();
            } catch (Throwable t) {
                LOGGER.error("Shader sanity-check crashed (vulkium stays enabled)", t);
            }
            // Allocate the region ledger and wire the section-ingest path. Cheap (~8MB
            // device-local meta slab); keeping it lazy would move allocation onto the first
            // render frame which is uglier.
            try {
                regionManager = new RegionManager(MAX_REGIONS);
                SectionManager.get().bindRegionManager(regionManager);
                LOGGER.info("RegionManager bound ({} regions × {} sections/region).",
                    MAX_REGIONS, RegionManager.SECTIONS_PER_REGION);
            } catch (Throwable t) {
                LOGGER.error("RegionManager init failed (disabling vulkium)", t);
                enabled = false;
            }
        } else {
            enabled = false;
            LOGGER.warn("Vulkium DISABLED. Reason: {}", probe.reason());
            LOGGER.warn("Vanilla MC terrain rendering will be used instead of vulkium's mesh-shader pipeline.");
        }
    }

    private static void onClientStopping(Minecraft client) {
        // Free VK resources before Mojang's VulkanDevice teardown yanks the allocator out from
        // under us. Anything we allocated against MojangVulkanBridge.vma() must close here.
        if (regionManager != null) {
            try {
                regionManager.close();
            } catch (Throwable t) {
                LOGGER.warn("RegionManager close failed", t);
            }
            regionManager = null;
            SectionManager.get().bindRegionManager(null);
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static VulkanDetect.ProbeResult probe() { return probe; }

    public static RegionManager regionManager() { return regionManager; }

    public static String getVersion() {
        return Vulkium.class.getPackage().getImplementationVersion() != null
            ? Vulkium.class.getPackage().getImplementationVersion()
            : "dev";
    }

    public static Logger logger() { return LOGGER; }
}
