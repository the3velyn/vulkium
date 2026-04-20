package me.cortex.vulkium.mixin.camera;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkium.blaze3d.BobViewTap;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the modelview matrix AFTER {@code bobHurt}+{@code bobView} have been applied to
 * the local {@code PoseStack} in {@code GameRenderer.renderLevel}. Vulkium's own MVP needs
 * this to include player-motion bobbing and damage-shake; terrain otherwise stays rigid
 * while vanilla entities/particles wobble.
 *
 * <p>The catch-all capture on {@code LevelRenderer.renderLevel}'s projection arg (which
 * would have picked up portal warp + nausea distortion for free) kept failing the mixin
 * target-scan on MC 26.2's dev mappings — @Inject and @ModifyArg both returned
 * "Scanned 0 target(s)" despite byte-for-byte descriptor matches. Reverted to this narrower
 * bob capture; portal + nausea distortion are not yet picked up (follow-up).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {

    /** Reset BobViewTap before the bob sequence starts so early-returning methods (camera
     *  isn't living / isn't player) don't carry a stale matrix from a previous frame. */
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void vulkium$resetBobAtFrameStart(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.invalidate();
    }

    @Inject(method = "bobHurt", at = @At("TAIL"))
    private void vulkium$captureBobHurt(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.setPose(pose.last().pose());
    }

    @Inject(method = "bobView", at = @At("TAIL"))
    private void vulkium$captureBobView(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.setPose(pose.last().pose());
    }
}
