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
 * <p>Hooks {@code LevelRenderer.renderLevel} at HEAD and captures the projection argument —
 * that parameter already has every view-space effect baked in (bob, portal warp, nausea,
 * screen-effect-scale, mods/datapacks that tweak the composed projection). One mixin covers
 * every distortion source automatically.
 *
 * <p>The method is declared as {@code renderLevel} in MC 26.2 official mappings (per javap)
 * but Fabric's own {@code fabric-rendering-v1} mixin on the same method uses
 * {@code method=["render"]} in its {@code @Inject} annotation — likely the name Fabric
 * Loom's mixin AP expects before the dev-environment remap kicks in. Using the same name
 * so Loom's refmap generator resolves it the same way.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererProjMixin {

    @Inject(method = {"render", "renderLevel"}, at = @At("HEAD"))
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
