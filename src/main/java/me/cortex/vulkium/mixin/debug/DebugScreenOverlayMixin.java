package me.cortex.vulkium.mixin.debug;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.RegionManager;
import me.cortex.vulkium.managers.SectionCapture;
import me.cortex.vulkium.managers.SectionManager;
import me.cortex.vulkium.render.FrameDriver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Appends vulkium diagnostic lines to MC's F3 debug overlay. Shows: enabled/disabled state,
 * probe reason, frame count, captured section count, region count, throughput totals.
 *
 * <p>Targets {@code extractLines} rather than {@code extractRenderState} so we piggy-back on
 * MC's list-building rather than rendering text ourselves.
 */
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {

    @Inject(method = "extractLines",
            at = @At("TAIL"))
    private void vulkium$appendStats(GuiGraphicsExtractor extractor, List<String> lines, boolean rightHand,
                                     CallbackInfo ci) {
        // Only show on the left-hand column (game info). The right-hand column is system info.
        if (rightHand) return;

        lines.add("");
        if (!Vulkium.isEnabled()) {
            String reason = Vulkium.probe() == null ? "probing…" : Vulkium.probe().reason();
            lines.add(String.format("[\u00a7bvulkium\u00a7r] disabled (%s)", reason));
            return;
        }

        SectionManager sm = SectionManager.get();
        RegionManager rm = Vulkium.regionManager();
        long captured = SectionCapture.sectionsCaptured();
        long vbMb = SectionCapture.vertexBytesTotal() / (1024 * 1024);
        long ibMb = SectionCapture.indexBytesTotal() / (1024 * 1024);

        lines.add(String.format(
            "[\u00a7bvulkium\u00a7r] frames=%d captured=%d liveSections=%d regions=%d vb=%dMB ib=%dMB",
            FrameDriver.frameCount(), captured, sm.liveView().size(),
            rm == null ? 0 : rm.regionCount(), vbMb, ibMb));
    }
}
