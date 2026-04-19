package me.cortex.vulkium;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import me.cortex.vulkium.blaze3d.VulkanDetect;
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

    @Override
    public void onInitializeClient() {
        LOGGER.info("Vulkium {} loaded (MC {}). Initialization deferred until Mojang's renderer is ready.",
            getVersion(), Minecraft.getInstance() == null ? "<uninit>" : "initialized");

        // We can't feature-probe at onInitializeClient() because RenderSystem.getDevice() isn't
        // yet populated — Mojang's backend is created later during client boot. Defer to a
        // lifecycle event (CLIENT_STARTED, or we hook later via a mixin into
        // com.mojang.blaze3d.systems.RenderSystem.initRenderer).
        ClientLifecycleEvents.CLIENT_STARTED.register(Vulkium::onClientStarted);
    }

    private static void onClientStarted(Minecraft client) {
        probe = VulkanDetect.probe();
        if (probe.meetsVulkiumGate()) {
            enabled = true;
            LOGGER.info("Vulkium ENABLED. {}", probe);
            MojangVulkanBridge.logBackendInfo();
        } else {
            enabled = false;
            LOGGER.warn("Vulkium DISABLED. Reason: {}", probe.reason());
            LOGGER.warn("Vanilla MC terrain rendering will be used instead of vulkium's mesh-shader pipeline.");
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static VulkanDetect.ProbeResult probe() { return probe; }

    public static String getVersion() {
        return Vulkium.class.getPackage().getImplementationVersion() != null
            ? Vulkium.class.getPackage().getImplementationVersion()
            : "dev";
    }

    public static Logger logger() { return LOGGER; }
}
