package me.cortex.vulkium.mixin.chunk;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code LevelExtractor.sectionUpdateTracker} so vulkium's eviction path can mark
 * re-captured sections dirty. Needed because {@link
 * me.cortex.vulkium.managers.SectionManager#sweepKeepDistance} now evicts by radius at the
 * "Vanilla" (32) keep-distance setting — previously it relied on MC's own unload signal
 * via {@code ClientLevel.hasChunk}, which never fires on a singleplayer integrated server
 * with a cooperating server view distance (chunks stay loaded indefinitely → "Vanilla"
 * behaved identically to "Keep All").
 *
 * <p>After radius eviction, we call {@code sectionUpdateTracker.setDirty(sx, sy, sz, true)}
 * on the evicted sections so MC's compile pipeline re-queues them next time they land in
 * view — without this, re-approaching an evicted section leaves it invisible because MC
 * thinks it's already compiled (its internal {@code SectionMesh} cache is warm).
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Accessor("sectionUpdateTracker")
    SectionUpdateTracker vulkium$getSectionUpdateTracker();
}
