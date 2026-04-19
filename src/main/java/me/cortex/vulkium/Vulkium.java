package me.cortex.vulkium;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import me.cortex.vulkium.blaze3d.VulkanDetect;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionManager;
import me.cortex.vulkium.render.FrameDriver;
import me.cortex.vulkium.vk.ComputeSmokeTest;
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

        // Frame hooks must be registered at init (not CLIENT_STARTED) — the events fire from
        // world render which can start before CLIENT_STARTED under some launch paths. The
        // handlers early-out on isEnabled() when the probe hasn't yet concluded.
        FrameDriver.register();
        VulkiumKeys.register();
    }

    private static void onClientStarted(Minecraft client) {
        VulkiumConfig cfg = VulkiumConfig.get();
        if (cfg.forceDisable) {
            enabled = false;
            LOGGER.warn("Vulkium DISABLED by config (forceDisable=true).");
            return;
        }

        probe = VulkanDetect.probe();

        // Shader sanity-check + compute smoke-test are both useful on any Vulkan-backed GPU,
        // not just ones that pass vulkium's full mesh-shader gate. They validate shaderc's
        // V6 translation output + the compute-pipeline plumbing independent of the
        // mesh-shader hardware requirement.
        boolean mojangVulkanLive = probe.vulkanBackendActive();
        if (mojangVulkanLive && cfg.runShaderSanityCheck) {
            try {
                ShaderSanityCheck.runAll();
            } catch (Throwable t) {
                LOGGER.error("Shader sanity-check crashed", t);
            }
        }
        // ComputeSmokeTest exercises SHADER_DEVICE_ADDRESS_BIT via a buffer-reference push
        // constant — only meaningful on devices where Mojang enabled bufferDeviceAddress.
        // On other devices vmaCreateBuffer would fail with INITIALIZATION_FAILED.
        if (mojangVulkanLive && probe.bufferDeviceAddress() && cfg.runComputeSmokeTest) {
            try {
                ComputeSmokeTest.run();
            } catch (Throwable t) {
                LOGGER.error("Compute smoke-test crashed", t);
            }
        } else if (mojangVulkanLive && cfg.runComputeSmokeTest) {
            LOGGER.info("Skipping compute smoke-test: bufferDeviceAddress not enabled on this GPU.");
        }

        if (probe.meetsVulkiumGate()) {
            enabled = true;
            LOGGER.info("Vulkium ENABLED. {}", probe);
            MojangVulkanBridge.logBackendInfo();
            try {
                regionManager = new RegionManager(cfg.maxRegions);
                SectionManager.get().bindRegionManager(regionManager);
                LOGGER.info("RegionManager bound ({} regions × {} sections/region).",
                    cfg.maxRegions, RegionManager.SECTIONS_PER_REGION);
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
        try {
            me.cortex.vulkium.render.Renderer.get().shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Renderer shutdown failed", t);
        }
        try {
            me.cortex.vulkium.blaze3d.MojangAtlasTap.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("MojangAtlasTap shutdown failed", t);
        }
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
