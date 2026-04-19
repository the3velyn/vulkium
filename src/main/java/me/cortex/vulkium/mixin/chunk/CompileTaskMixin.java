package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.managers.SectionCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tags the worker thread with the owning section's packed {@code SectionPos.asLong} at
 * compile entry/exit. The subsequent {@link net.minecraft.client.renderer.chunk.CompiledSectionMesh}
 * ctor mixin reads the thread-local when the ctor fires, so captured meshes carry a real
 * position key instead of a 0 sentinel.
 *
 * <p>{@code CompileTask} extends {@code SectionTask}, which exposes {@code getRenderOrigin()} —
 * a section-aligned block position. We convert that to a {@code SectionPos} long. No access to
 * the synthetic outer-class reference ({@code this$1}) is needed.
 */
@Mixin(targets = "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskMixin {

    @Inject(method = "doTask",
            at = @At("HEAD"))
    private void vulkium$pushCompilingSection(SectionBufferBuilderPack pack,
                                              CallbackInfoReturnable<?> cir) {
        BlockPos origin = ((net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask) (Object) this)
            .getRenderOrigin();
        SectionCapture.beginSectionCompile(SectionPos.asLong(origin));
    }

    @Inject(method = "doTask",
            at = @At("RETURN"))
    private void vulkium$popCompilingSection(SectionBufferBuilderPack pack,
                                             CallbackInfoReturnable<?> cir) {
        SectionCapture.endSectionCompile();
    }
}
