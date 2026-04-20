package me.cortex.vulkium.mixin.camera;

import me.cortex.vulkium.blaze3d.BobViewTap;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Catch-all capture of MC's view-space projection at the exact moment it's finalized.
 *
 * <p>Previous attempt targeted {@code LevelRenderer.renderLevel}'s 5th arg assuming it held
 * the bob-composed projection. A deeper bytecode read of
 * {@code GameRenderer.renderLevel} showed that arg is actually
 * {@code camState.viewRotationMatrix}, not the projCopy — multiplying our captured matrix
 * by viewRotation again produced a compound rotation, which collapsed almost all terrain
 * to off-frustum positions (the "few blocks at specific angles" symptom).
 *
 * <p>The REAL final projection is composed into a local {@code projCopy} Matrix4f:
 * <ol>
 *   <li>{@code projCopy = new Matrix4f(camState.projectionMatrix)}</li>
 *   <li>{@code projCopy.mul(pose.last().pose())}  // bobHurt+bobView applied</li>
 *   <li>portal spin rotate / scale / un-rotate</li>
 *   <li>{@code projectionMatrixBuffer.getBuffer(projCopy)}  // converts Matrix4f → GpuBufferSlice</li>
 *   <li>{@code RenderSystem.setProjectionMatrix(slice, PERSPECTIVE)}</li>
 *   <li>{@code levelRenderer.renderLevel(..., viewRotationMatrix, ...)}  // passes viewRot, not projCopy</li>
 * </ol>
 * We hook step 4 — {@code @ModifyArg} on
 * {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)}'s single argument. The arg at that
 * moment is projCopy with every view-space effect (bob + portal + nausea + any future /
 * modded distortion) already composed. We sniff it, store it in {@link BobViewTap}, and
 * return it unchanged so MC's rendering is undisturbed.
 */
@Mixin(GameRenderer.class)
public abstract class LevelRendererProjMixin {

    @ModifyArg(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;"
                        + "getBuffer(Lorg/joml/Matrix4f;)"
                        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        remap = false)
    private Matrix4f vulkium$captureComposedProjection(Matrix4f projCopy) {
        BobViewTap.setProjection(projCopy);
        return projCopy;
    }
}
