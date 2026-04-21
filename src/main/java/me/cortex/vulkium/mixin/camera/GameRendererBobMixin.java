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
 * the local {@code PoseStack} in {@code GameRenderer.renderLevel}. Provides a bob-only
 * fallback to the main catch-all capture in {@link LevelRendererProjMixin}.
 *
 * <p>Frame-time preference in {@code FrameDriver.updateMvpFromCamera}:
 * <ol>
 *   <li>If {@code LevelRendererProjMixin}'s {@code @ModifyArg} on
 *       {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} applied, read the full composed
 *       projection from {@link BobViewTap#readProjection} (bob + portal warp + nausea +
 *       any view-space mod distortion).</li>
 *   <li>Otherwise fall back to this mixin's pose (bob only) composed with
 *       {@code camState.projectionMatrix}.</li>
 * </ol>
 * Both paths coexist so that vulkium still looks correct even if the mixin target-scan for
 * one misses on a future MC snapshot.
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
