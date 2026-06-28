package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.managers.SectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Sodium's chunk-build results into vulkium's {@link SectionManager}. Fires on the
 * Sodium worker thread immediately after {@code ChunkBuilderMeshingTask#execute} produces a
 * {@link ChunkBuildOutput}. We hand the output off via {@code offerFromSodium}, which copies
 * Sodium's NativeBuffer (Sodium recycles it after this method returns to its caller).
 *
 * <p>{@code remap = false} because Sodium ships with official mappings — MC's Yarn/intermediary
 * remap doesn't apply to {@code net.caffeinemc.mods.sodium.*} classes.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/sodium");

    @Inject(method = "execute", at = @At("RETURN"))
    private void vulkium$captureChunkBuildOutput(CallbackInfoReturnable<ChunkBuildOutput> cir) {
        ChunkBuildOutput output = cir.getReturnValue();
        if (output == null || output.section == null) return;
        try {
            long sectionPosKey = output.section.getPosition().asLong();
            SectionManager.get().offerFromSodium(sectionPosKey, output);
        } catch (Throwable t) {
            // Defensive — a thrown exception on the Sodium worker thread silently kills the
            // worker. Log + swallow so the rest of Sodium's pipeline isn't poisoned by a
            // vulkium-side bug.
            LOGGER.error("offerFromSodium threw, ingest dropped for this section", t);
        }
    }
}
