package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip MC's per-frame section-occlusion-graph BFS when vulkium is drawing. The graph
 * output feeds MC's visible-sections list, which feeds MC's terrain draw path — which
 * we already cancel in {@code ChunkSectionsToRenderMixin}. Updating the graph every
 * frame is CPU work with zero downstream effect while vulkium is active.
 *
 * <p>Entities, block-entities, outlines, block-breaking progress all use separate
 * chunk-lookup paths that don't consult the occlusion graph, so cutting it off here
 * shouldn't affect any other subsystem. If a future feature starts depending on MC's
 * visible-section list, move the gate to something more granular.
 *
 * <p>Auto-reverts the moment vulkium is disabled via options — the graph resumes
 * updating on the next frame.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphSkipMixin {

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void vulkium$skipUpdateWhenDrawing(CallbackInfo ci) {
        if (Vulkium.isEnabled() && VulkiumConfig.get().drawTerrain) {
            ci.cancel();
        }
    }
}
