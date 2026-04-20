package me.cortex.vulkium.mixin.camera;

import me.cortex.vulkium.blaze3d.BobViewTap;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Catch-all projection capture for vulkium's MVP.
 *
 * <p>{@code GameRenderer.renderLevel} composes bob + portal warp + nausea + screenEffectScale
 * into the projection matrix, then hands that off to {@code LevelRenderer.renderLevel}. Hook
 * that call site with {@code @ModifyArg} on the {@code Matrix4fc} argument (position 4 in
 * LevelRenderer's {@code renderLevel}): we sniff the final view-space projection as it's
 * about to be passed through, store it in {@link BobViewTap}, and return the unchanged matrix
 * so MC's own rendering continues unaffected.
 *
 * <p>@ModifyArg matches on the INVOKE instruction inside GameRenderer.renderLevel rather than
 * the target method's own declaration, which avoids a class of @Inject target-resolution
 * failures ("could not find any targets matching 'renderLevel(...)' in LevelRenderer") that
 * surfaced on the MC 26.2 dev mappings.
 *
 * <p>One capture covers every vanilla view-effect — bob, portal, nausea, screen-effect-scale —
 * and automatically follows any future view-effect MC adds to this composition.
 */
@Mixin(GameRenderer.class)
public abstract class LevelRendererProjMixin {

    @ModifyArg(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel"
                        + "(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                        + "Lnet/minecraft/client/DeltaTracker;"
                        + "Z"
                        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                        + "Lorg/joml/Matrix4fc;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                        + "Lorg/joml/Vector4f;"
                        + "Z"
                        + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"),
        index = 4)
    private Matrix4fc vulkium$captureFinalProjection(Matrix4fc projection) {
        BobViewTap.setProjection(projection);
        return projection;
    }
}
