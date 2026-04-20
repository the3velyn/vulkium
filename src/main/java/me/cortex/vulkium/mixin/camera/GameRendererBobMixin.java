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
 * this to include player-motion bobbing and damage/nausea distortion — otherwise terrain
 * stays rigid while vanilla entities/particles wobble.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobMixin {

    /** Reset BobViewTap at the HEAD of bobHurt — this is the first bob hook in
     *  GameRenderer.renderLevel's bob sequence, which runs BEFORE Fabric's LevelRenderEvents
     *  fire. Invalidating in FrameDriver.onStartMain would wipe out captures that already
     *  happened this frame. Capturing the pose at TAIL of each bob method lets either bob's
     *  accumulated matrix flow through to our MVP. */
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void vulkium$resetBobAtFrameStart(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.invalidate();
    }

    @Inject(method = "bobHurt", at = @At("TAIL"))
    private void vulkium$captureBobHurt(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.set(pose.last().pose());
    }

    @Inject(method = "bobView", at = @At("TAIL"))
    private void vulkium$captureBobView(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.set(pose.last().pose());
    }
}
