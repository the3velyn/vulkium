package me.cortex.vulkium.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observation-only hook on Sodium's section compile path. Fires once per section MC tells Sodium
 * to recompile. We capture nothing yet — this mixin's job is just to prove the integration
 * scaffolding works: vulkium loads alongside Sodium, the mixin descriptor matches, and the
 * injector actually fires on the worker thread Sodium uses.
 *
 * <p>Later this hook will route the compiled {@link ChunkBuildOutput#meshes} into vulkium's
 * SectionManager so the mesh-shader pipeline gets fed with Sodium-produced geometry instead of
 * running through Sodium's own region-upload path.
 *
 * <p>{@code remap = false} because Sodium ships with official mappings — MC's Yarn/intermediary
 * remap doesn't apply to {@code net.caffeinemc.mods.sodium.*} classes.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/sodium");
    private static final AtomicLong CAPTURES = new AtomicLong();

    @Inject(method = "execute", at = @At("RETURN"))
    private void vulkium$observeChunkBuildOutput(CallbackInfoReturnable<ChunkBuildOutput> cir) {
        ChunkBuildOutput output = cir.getReturnValue();
        if (output == null) return;
        long n = CAPTURES.incrementAndGet();
        if (n <= 8 || n % 1024 == 0) {
            LOGGER.info("Observed Sodium ChunkBuildOutput #{} — meshes={} layers, blockingTask={}",
                n, output.meshes != null ? output.meshes.size() : 0, output.blockingTask);
        }
    }
}
