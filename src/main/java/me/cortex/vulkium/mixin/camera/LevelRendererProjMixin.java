package me.cortex.vulkium.mixin.camera;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import me.cortex.vulkium.blaze3d.BobViewTap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catch-all projection capture for vulkium's MVP.
 *
 * <p>{@code GameRenderer.renderLevel} composes a series of view-space effects into its
 * projection copy before calling {@code LevelRenderer.renderLevel}:
 * <ol>
 *   <li>{@code bobHurt} — damage-shake and death-roll.</li>
 *   <li>{@code bobView} — walking bob translation + rotation.</li>
 *   <li>Portal-effect screen distortion.</li>
 *   <li>Nausea (confusion) screen warp.</li>
 *   <li>Any {@code screenEffectScale}-modulated twist.</li>
 * </ol>
 * All of these land in the {@code Matrix4fc} that's then passed as the 5th parameter to
 * {@code LevelRenderer.renderLevel}. Hooking that parameter gives us the finished projection
 * matrix with every view-effect already baked in — bob, portal, nausea, and anything MC
 * adds in the future — with a single mixin instead of chasing each effect individually.
 *
 * <p>FrameDriver's onEndMain then composes MVP as {@code capturedProjection × viewRotation},
 * so terrain tracks vanilla's view-effect compositing automatically.
 *
 * <p>Replaces (and supersedes) the older per-bob-method capture in {@code GameRendererBobMixin} —
 * that one only caught bob and none of the other distortions.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererProjMixin {

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
               + "Lnet/minecraft/client/DeltaTracker;"
               + "Z"
               + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
               + "Lorg/joml/Matrix4fc;"
               + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
               + "Lorg/joml/Vector4f;"
               + "Z"
               + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V",
        at = @At("HEAD"))
    private void vulkium$captureFinalProjection(GraphicsResourceAllocator allocator,
                                                 DeltaTracker delta,
                                                 boolean cullSpectator,
                                                 CameraRenderState camera,
                                                 Matrix4fc projection,
                                                 GpuBufferSlice fogBuffer,
                                                 Vector4f fogColor,
                                                 boolean visualizeChunks,
                                                 ChunkSectionsToRender chunkSections,
                                                 CallbackInfo ci) {
        BobViewTap.setProjection(projection);
    }
}
