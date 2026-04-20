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

    @Inject(method = "bobView", at = @At("TAIL"))
    private void vulkium$captureBobView(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.set(pose.last().pose());
    }

    @Inject(method = "bobHurt", at = @At("TAIL"))
    private void vulkium$captureBobHurt(CameraRenderState cam, PoseStack pose, CallbackInfo ci) {
        BobViewTap.set(pose.last().pose());
    }
}
