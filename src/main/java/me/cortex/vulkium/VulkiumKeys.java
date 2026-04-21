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

    // DEV_ONLY_LOOKAROUND — remove before release.
    // File-based yaw-spin trigger. Prior-baseline test methodology was "RD=32, player
    // rotates through 360° to force all chunks in the loaded radius to compile" — without
    // it, MC only streams the cone in front of the camera and FPS numbers aren't comparable
    // to the pre-dev-branch baseline. Bash-side `touch /tmp/vulkium-lookaround` spins the
    // player's yaw at a fixed rate for LOOKAROUND_TOTAL_TICKS client ticks (~4s at 20 tps).
    // Sampled on the same Nth-tick cadence as the screenshot trigger.
    private static final Path LOOKAROUND_TRIGGER = Path.of("/tmp/vulkium-lookaround");
    private static final int LOOKAROUND_TOTAL_TICKS = 80;
    private static final float LOOKAROUND_DEG_PER_TICK = 360.0f / LOOKAROUND_TOTAL_TICKS;
    private static int lookaroundTicksRemaining = 0;
    // END DEV_ONLY_LOOKAROUND

    // DEV_ONLY_QUARTER_TURN — remove before release.
    // Instant +90° yaw rotate trigger. Used by the deep-test script to capture four
    // screenshots at cardinal view directions (N, E, S, W) without the motion-blur you'd
    // get from the smooth LOOKAROUND rotation. Each touch of the file snaps yaw by exactly
    // 90° on the next processed tick. Like the other triggers, checked on the ~4 Hz poll.
    private static final Path QUARTER_TURN_TRIGGER = Path.of("/tmp/vulkium-rotate-90");
    // END DEV_ONLY_QUARTER_TURN

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
        // DEV_ONLY_LOOKAROUND (shares this poll counter) — remove before release.
        if (++screenshotTickCounter >= SCREENSHOT_POLL_PERIOD_TICKS) {
            screenshotTickCounter = 0;
            maybeTakeTriggeredScreenshot(mc);
            maybeStartLookaround(mc);
            maybeQuarterTurn(mc);
        }
        stepLookaround(mc); // runs every tick, not throttled
        // END DEV_ONLY_SCREENSHOT_HOOK / DEV_ONLY_LOOKAROUND
    }

    // DEV_ONLY_LOOKAROUND — remove before release.
    private static void maybeStartLookaround(Minecraft mc) {
        if (!Files.exists(LOOKAROUND_TRIGGER)) return;
        try {
            Files.deleteIfExists(LOOKAROUND_TRIGGER);
        } catch (Exception e) {
            LOGGER.warn("Could not delete lookaround trigger {}: {} — skipping",
                LOOKAROUND_TRIGGER, e.getMessage());
            return;
        }
        if (mc.player == null) {
            LOGGER.warn("Lookaround trigger fired but mc.player is null (world not loaded?)");
            return;
        }
        lookaroundTicksRemaining = LOOKAROUND_TOTAL_TICKS;
        LOGGER.info("[vulkium-test] lookaround START ({} ticks = ~{}s at 20tps)",
            LOOKAROUND_TOTAL_TICKS, LOOKAROUND_TOTAL_TICKS / 20);
    }

    // DEV_ONLY_QUARTER_TURN — remove before release.
    private static void maybeQuarterTurn(Minecraft mc) {
        if (!Files.exists(QUARTER_TURN_TRIGGER)) return;
        try {
            Files.deleteIfExists(QUARTER_TURN_TRIGGER);
        } catch (Exception e) {
            LOGGER.warn("Could not delete quarter-turn trigger {}: {} — skipping",
                QUARTER_TURN_TRIGGER, e.getMessage());
            return;
        }
        if (mc.player == null) {
            LOGGER.warn("Quarter-turn trigger fired but mc.player is null (world not loaded?)");
            return;
        }
        float prevYaw = mc.player.getYRot();
        float newYaw = prevYaw + 90.0f;
        mc.player.yRotO = prevYaw;
        mc.player.setYRot(newYaw);
        LOGGER.info("[vulkium-test] quarter-turn: yaw {} → {}", prevYaw, newYaw);
    }
    // END DEV_ONLY_QUARTER_TURN

    private static void stepLookaround(Minecraft mc) {
        if (lookaroundTicksRemaining <= 0) return;
        if (mc.player == null) { lookaroundTicksRemaining = 0; return; }
        // Advance yaw client-side; integrated server picks up from player-move packets.
        // Increment the prev-frame yaw so camera interpolation stays smooth across the spin
        // — using setYRot alone leaves yRotO at the previous tick's value, producing a
        // single-frame jitter the screenshot could catch.
        float newYaw = mc.player.getYRot() + LOOKAROUND_DEG_PER_TICK;
        mc.player.yRotO = mc.player.getYRot();
        mc.player.setYRot(newYaw);
        lookaroundTicksRemaining--;
        if (lookaroundTicksRemaining == 0) {
            LOGGER.info("[vulkium-test] lookaround DONE — all 360° swept");
        }
    }
    // END DEV_ONLY_LOOKAROUND

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
