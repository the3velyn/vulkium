package me.cortex.vulkium.mixin.gui;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds {@code VulkiumConfig.extraRd} chunks to MC's effective render distance.
 *
 * <p>{@code Options.getEffectiveRenderDistance()} is the canonical read-site MC uses when it
 * needs the "active" RD (which differs from the raw slider value when the world has its own
 * simulation-distance cap, etc.). {@link net.minecraft.client.Camera#update} reads this to
 * compute {@code depthFar} (the projection far plane), so bumping it here extends vulkium's
 * visible frustum without needing to touch the projection matrix directly. The internal SP
 * server also reads it to decide how many chunks to load — which is why the tooltip flags
 * the extra-RD slider as SP-only.
 *
 * <p>We gate on {@link Vulkium#isEnabled()} so toggling the master "Vulkium enabled" switch
 * (which sets {@code forceDisable}) also instantly drops the override.
 */
@Mixin(Options.class)
public abstract class OptionsRenderDistanceMixin {

    @Inject(method = "getEffectiveRenderDistance",
            at = @At("RETURN"),
            cancellable = true)
    private void vulkium$addExtraRd(CallbackInfoReturnable<Integer> cir) {
        if (!Vulkium.isEnabled()) return;
        int extra = VulkiumConfig.get().extraRd;
        if (extra <= 0) return;
        cir.setReturnValue(cir.getReturnValue() + extra);
    }
}
