package me.cortex.vulkium.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin on Sodium's {@link ChunkBuildOutput}. Empty for now — exists so that when
 * we add per-output state attachment (e.g. a flag tracking whether vulkium ingested this output)
 * the mixin descriptor is already loaded and discovered by the mixin processor.
 *
 * <p>Future: implement a marker interface mixed into ChunkBuildOutput so vulkium's
 * RenderRegionManager hook can ignore outputs we've already consumed, avoiding double-upload.
 *
 * <p>{@code remap = false} per Sodium's mapping convention (official names, not Yarn).
 */
@Mixin(value = ChunkBuildOutput.class, remap = false)
public abstract class ChunkBuildOutputMixin {
}
