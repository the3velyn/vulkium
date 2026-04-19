package me.cortex.vulkium.render;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.blaze3d.VulkanDetect;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionCapture;
import me.cortex.vulkium.managers.SectionManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Vulkium's F3-style diagnostic overlay, rendered via Fabric's {@link HudElementRegistry}
 * rather than by mixing into MC's {@code DebugScreenOverlay}. MC 26.2 reworked F3 rendering
 * onto {@code GuiGraphicsExtractor} — the old {@code extractLines} list is no longer the
 * source of truth for what gets drawn, so an {@code @Inject TAIL} on it silently no-ops.
 *
 * <p>Only renders when MC's debug overlay is active (F3 held or toggled).
 */
public final class VulkiumHudOverlay implements HudElement {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("vulkium", "diag_overlay");

    public static void register() {
        HudElementRegistry.addLast(ID, new VulkiumHudOverlay());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        // Piggy-back on vanilla's debug overlay gate so we only draw when F3 is held/toggled.
        if (mc.getDebugOverlay() == null || !mc.getDebugOverlay().showDebugScreen()) return;

        String line1;
        String line2 = null;
        if (!Vulkium.isEnabled()) {
            VulkanDetect.ProbeResult probe = Vulkium.probe();
            String reason = probe == null ? "probing\u2026" : probe.reason();
            line1 = "[vulkium] disabled \u2014 " + reason;
        } else {
            SectionManager sm = SectionManager.get();
            RegionManager rm = Vulkium.regionManager();
            VisibilityTracker vt = Renderer.get().visibility();
            long cullUs = vt == null ? 0L : vt.lastUpdateDurationNs() / 1000L;
            long hzbUs  = Renderer.get().hzbLastBuildNs() / 1000L;
            long captured = SectionCapture.sectionsCaptured();
            long vbMb = SectionCapture.vertexBytesTotal() / (1024L * 1024L);
            long ibMb = SectionCapture.indexBytesTotal()  / (1024L * 1024L);

            line1 = String.format("[vulkium] frames=%d captured=%d live=%d regions=%d visible=%d",
                FrameDriver.frameCount(),
                captured,
                sm.liveView().size(),
                rm == null ? 0 : rm.regionCount(),
                vt == null ? 0 : vt.visibleRegionCount());
            line2 = String.format("[vulkium] cull=%dus hzb=%dus vb=%dMB ib=%dMB",
                cullUs, hzbUs, vbMb, ibMb);
        }

        // Cyan, top-right corner so we don't collide with vanilla's F3 columns.
        int color = 0xFF5FD7FF;
        int pad = 4;
        int y = mc.getWindow().getGuiScaledHeight() - mc.font.lineHeight * (line2 != null ? 2 : 1) - pad;
        int x = pad;
        extractor.text(mc.font, line1, x, y, color);
        if (line2 != null) {
            extractor.text(mc.font, line2, x, y + mc.font.lineHeight, color);
        }
    }
}
