package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.Vulkium;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Sodium's terrain draw when Vulkium owns rendering. Sodium calls
 * {@code DefaultChunkRenderer#render} once per {@code TerrainRenderPass} per frame (SOLID,
 * CUTOUT, TRANSLUCENT); we cancel ALL three so Vulkium's mesh-shader pipeline can render
 * those layers without double-draw + Z-fight.
 *
 * <p>The Stage 0 recon (see SODIUM_PORT.md) confirmed cancellation at HEAD is safe:
 * Sodium's bracketing {@code super.begin()/end()} sit inside this method, and the calling
 * {@code SodiumWorldRenderer.renderLayer} does nothing after the call. Skipping the whole
 * method leaves Sodium's state machine clean for the next pass.
 *
 * <p>Single backend coverage: {@code DefaultChunkRenderer.render} is the bottleneck for
 * both Sodium's GL and Vulkan draw paths (the backend split happens inside
 * {@code MultiDrawBatch.draw} further down). One cancel here suppresses both.
 *
 * <p>{@code remap = false} per the Sodium-mixin convention.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkium$cancelTerrainDraw(CallbackInfo ci) {
        if (Vulkium.isEnabled()) {
            ci.cancel();
        }
    }
}
