package me.cortex.vulkium.mixin.chunk;

import com.mojang.blaze3d.vertex.VertexSorting;
import me.cortex.vulkium.managers.SectionCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Worker-thread section-key plumbing for vulkium's mesh capture.
 *
 * <p>Replaces the earlier attempt at mixing into the private inner classes
 * {@code RebuildTask} / {@code ResortTransparencyTask} (Fabric's mixin loader couldn't
 * resolve those nested private types at class-load time, so the injections never applied
 * and every captured section got the {@code UNKNOWN_SECTION} sentinel key — no uploads,
 * no rendering).
 *
 * <p>{@link SectionCompiler#compile} is public, public-classed, and receives the
 * {@link SectionPos} as its first argument. The subsequent {@code CompiledSectionMesh}
 * ctor (see {@code CompiledSectionMeshMixin}) runs on the same worker thread, so setting
 * the thread-local at HEAD is enough to carry the key through.
 *
 * <p>No @RETURN pair — clearing at compile's return would fire BEFORE the ctor reads it
 * (the ctor is invoked by the caller, not inside compile), zeroing every captured section
 * back to UNKNOWN_SECTION. Leaving the thread-local set between compiles is harmless
 * because every next compile on this thread overwrites it at HEAD before the next ctor
 * reads it.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Inject(method = "compile", at = @At("HEAD"))
    private void vulkium$pushCompilingSection(SectionPos sectionPos,
                                               RenderSectionRegion region,
                                               VertexSorting sorting,
                                               SectionBufferBuilderPack buffers,
                                               CallbackInfoReturnable<SectionCompiler.Results> cir) {
        // Stash the section key on this worker thread so CompiledSectionMeshMixin (fires in
        // the CompiledSectionMesh ctor ~60 bytecode later in the same RebuildTask.doTask frame)
        // picks it up. Crucially NO @At("RETURN") pair — clearing the thread-local on compile's
        // return would happen BEFORE the ctor reads it (since the ctor is invoked from the
        // caller, not from inside compile), zeroing every captured section back to
        // UNKNOWN_SECTION. The stale value between compiles is harmless because every next
        // compile on this thread overwrites it at HEAD before the next ctor reads it.
        SectionCapture.beginSectionCompile(sectionPos.asLong());
    }
}
