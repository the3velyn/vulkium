package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.Vulkium;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Sodium's translucent sort scheduling. Sodium's per-camera-move sort tasks
 * compute back-to-front quad orderings that flow into {@code ChunkSortOutput} →
 * {@code RenderRegionManager.uploadResults}'s index-buffer branch — which we've cancelled
 * (see {@link RenderRegionManagerMixin}). With the upload cancelled, the sort results
 * dead-end; the worker-thread compute is pure waste.
 *
 * <p>Vulkium's translucent path currently consumes Sodium's BUILD-order vertices verbatim
 * (no per-quad POV sort). When the user crosses a translucent-quad threshold the visual
 * artefact is "water/glass quads draw in a slightly wrong order from oblique angles" —
 * known limitation, documented in SODIUM_PORT.md. Sodium running sort tasks doesn't help
 * us; cancelling them just reclaims worker CPU.
 *
 * <p>Cancellation at {@code scheduleSort} HEAD covers both call sites:
 * {@code integrateTranslucentData} (after a fresh build) and {@code triggerSections}
 * (camera-movement-driven re-sort). Both flow through {@code this::scheduleSort} method
 * references, so a single HEAD cancel here suppresses every sort.
 *
 * <p>{@code remap = false} per the Sodium-mixin convention.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {

    @Inject(method = "scheduleSort(JZ)V", at = @At("HEAD"), cancellable = true)
    private void vulkium$cancelSortScheduling(long sectionPos, boolean isDirectTrigger, CallbackInfo ci) {
        if (Vulkium.isEnabled()) {
            ci.cancel();
        }
    }
}
