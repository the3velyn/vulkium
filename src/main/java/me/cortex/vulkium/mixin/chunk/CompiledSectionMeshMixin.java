package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.SectionCapture;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * At section-mesh construction, capture the compile {@link SectionCompiler.Results} so
 * vulkium's ingestion pipeline ({@link SectionCapture}) can see the raw {@code MeshData} for
 * each {@link net.minecraft.client.renderer.chunk.ChunkSectionLayer} before MC's own uber-buffer
 * upload happens.
 *
 * <p>This fires on the worker thread that compiled the section, not the render thread.
 */
@Mixin(CompiledSectionMesh.class)
public class CompiledSectionMeshMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;)V",
            at = @At("TAIL"))
    private void vulkium$captureMesh(TranslucencyPointOfView pov, SectionCompiler.Results results, CallbackInfo ci) {
        if (!Vulkium.isEnabled()) return;
        SectionCapture.onSectionMeshCompiled(results);
    }
}
