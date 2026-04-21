package me.cortex.vulkium;

import me.cortex.vulkium.blaze3d.VulkanDetect;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionCapture;
import me.cortex.vulkium.managers.SectionManager;
import me.cortex.vulkium.render.FrameDriver;
import me.cortex.vulkium.render.Renderer;
import me.cortex.vulkium.render.VisibilityTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime diagnostic input. Polls GLFW directly rather than using Fabric's key-binding API
 * (the key-binding module isn't packaged for our Fabric API version). As a result the key
 * doesn't appear in MC's Controls screen; users remap it (or add it to Controls) by editing
 * {@link #DUMP_KEY} in a future config.
 *
 * <p>F6: print vulkium internal state to chat + log.
 */
public final class VulkiumKeys {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/keys");

    /** GLFW scancode of the diagnostic-dump key. */
    private static final int DUMP_KEY = GLFW.GLFW_KEY_F6;

    /** Edge-detect so held-down doesn't spam. */
    private static boolean dumpPrev = false;

    // DEV_ONLY_SCREENSHOT_HOOK — remove before release.
    // File-based screenshot trigger for remote (headless-Wayland) testing: the dev session
    // is on a Linux + NVIDIA host reached over SSH from a machine without a GPU, so KDE's
    // xdg-desktop-portal capture path isn't reliable. Instead, bash-side `touch
    // /tmp/vulkium-screenshot` asks MC to screenshot via its own code path into
    // `run/screenshots/`, which is transferable over scp. Checked every Nth tick to amortize
    // the Files.exists syscall.
    private static final Path SCREENSHOT_TRIGGER = Path.of("/tmp/vulkium-screenshot");
    private static final int SCREENSHOT_POLL_PERIOD_TICKS = 5; // ~4 Hz at 20 tps
    private static int screenshotTickCounter = 0;
    // END DEV_ONLY_SCREENSHOT_HOOK

    private VulkiumKeys() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(VulkiumKeys::onClientTick);
        LOGGER.info("Vulkium keybinds active (F6 = dump diagnostics).");
    }

    private static void onClientTick(Minecraft mc) {
        long window = mc.getWindow() == null ? 0L : mc.getWindow().handle();
        if (window == 0L) return;

        boolean now = GLFW.glfwGetKey(window, DUMP_KEY) == GLFW.GLFW_PRESS;
        if (now && !dumpPrev) {
            dump(mc);
        }
        dumpPrev = now;

        // DEV_ONLY_SCREENSHOT_HOOK — remove before release.
        if (++screenshotTickCounter >= SCREENSHOT_POLL_PERIOD_TICKS) {
            screenshotTickCounter = 0;
            maybeTakeTriggeredScreenshot(mc);
        }
        // END DEV_ONLY_SCREENSHOT_HOOK
    }

    // DEV_ONLY_SCREENSHOT_HOOK — remove before release.
    private static void maybeTakeTriggeredScreenshot(Minecraft mc) {
        if (!Files.exists(SCREENSHOT_TRIGGER)) return;
        // Delete first so a second touch (even if Screenshot.grab fails for this frame)
        // leaves a clean trigger state. On delete failure, log and bail so we don't
        // fire repeatedly on the same trigger.
        try {
            Files.deleteIfExists(SCREENSHOT_TRIGGER);
        } catch (Exception e) {
            LOGGER.warn("Could not delete screenshot trigger {}: {} — skipping",
                SCREENSHOT_TRIGGER, e.getMessage());
            return;
        }
        if (mc.gameRenderer == null || mc.gameRenderer.mainRenderTarget() == null) {
            LOGGER.warn("Screenshot trigger fired but no mainRenderTarget available yet");
            return;
        }
        try {
            Screenshot.grab(mc.gameDirectory,
                mc.gameRenderer.mainRenderTarget(),
                msg -> LOGGER.info("[vulkium-ss] {}", msg.getString()));
        } catch (Throwable t) {
            LOGGER.warn("Screenshot.grab threw", t);
        }
    }
    // END DEV_ONLY_SCREENSHOT_HOOK

    private static void dump(Minecraft mc) {
        VulkanDetect.ProbeResult probe = Vulkium.probe();
        SectionManager sm = SectionManager.get();
        RegionManager rm = Vulkium.regionManager();
        VisibilityTracker vt = Renderer.get().visibility();

        String enabled = Vulkium.isEnabled() ? "ENABLED" : "DISABLED";
        String reason = probe == null ? "(no probe)" : probe.reason();

        String line1 = String.format("[vulkium] %s — %s", enabled, reason);
        String line2 = String.format(
            "[vulkium] frames=%d captured=%d liveSections=%d regions=%d visible=%d vb=%dMB ib=%dMB",
            FrameDriver.frameCount(),
            SectionCapture.sectionsCaptured(),
            sm.liveView().size(),
            rm == null ? 0 : rm.regionCount(),
            vt == null ? 0 : vt.visibleRegionCount(),
            SectionCapture.vertexBytesTotal() / (1024 * 1024),
            SectionCapture.indexBytesTotal() / (1024 * 1024));

        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(line1));
            mc.player.sendSystemMessage(Component.literal(line2));
        }
        LOGGER.info(line1);
        LOGGER.info(line2);
    }
}
