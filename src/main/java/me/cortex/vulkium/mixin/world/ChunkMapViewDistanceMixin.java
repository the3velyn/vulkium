package me.cortex.vulkium.mixin.world;

import net.minecraft.server.level.ChunkMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises MC's hard-coded server view-distance ceiling from 32 to 128 so integrated-server
 * singleplayer can actually stream chunks out to whatever the vanilla RD slider now goes
 * to (we pushed that up to 128 in {@link me.cortex.vulkium.mixin.gui.OptionsRenderDistanceMixin}).
 *
 * <p>The cap lives in {@code ChunkMap.setServerViewDistance(int)} as a literal
 * {@code Mth.clamp(v, 2, 32)} call. Without this second mixin, bumping the client slider
 * past 32 bumps the projection's far-plane but the integrated server still clamps chunk
 * streaming to 32 — user sees the frustum extend but no chunks load past 32.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapViewDistanceMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/rd");

    @ModifyConstant(method = "setServerViewDistance",
                    constant = @Constant(intValue = 32))
    private int vulkium$raiseServerViewDistanceCap(int originalMax) {
        return 128;
    }

    @Inject(method = "setServerViewDistance", at = @At("HEAD"))
    private void vulkium$logRequestedDistance(int requested, CallbackInfo ci) {
        LOGGER.info("ChunkMap.setServerViewDistance called with requested={}", requested);
    }
}
