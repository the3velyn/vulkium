package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.managers.SectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes Sodium's SYNTHETIC empty-section outputs into vulkium's eviction path.
 *
 * <p>Sodium's standard build pipeline runs {@code ChunkBuilderMeshingTask.execute} on a
 * worker thread and the existing {@link ChunkBuilderMeshingTaskMixin} captures the result
 * before it's processed. But for sections that are fully air (player broke last block,
 * the chunk slot was always empty, etc.), {@code createRebuildTask} returns null because
 * {@code LevelSlice.prepare} produces no slice. Sodium fast-paths this by synthesising
 * a {@link ChunkBuildOutput} with {@code BuiltSectionInfo.EMPTY} + an empty meshes map
 * directly and pushing it to {@code buildResults} — no worker task runs, so our
 * worker-side mixin never sees it.
 *
 * <p>The eviction path on vulkium's side is keyed on "Sodium emitted a build with empty
 * meshes" → {@code SectionManager.evict(...)}. To catch the synthetic case we hook
 * {@code applyBuildOutputs} which is the render-thread aggregator that receives
 * <strong>every</strong> build output (real + synthetic). For any empty-meshes
 * {@link ChunkBuildOutput} we offer it through the same routing as a real build —
 * {@link SectionManager#offerFromSodium} handles the empty case by queuing eviction.
 *
 * <p>Why not just cancel the existing worker-side mixin and route everything from here?
 * The worker mixin runs in parallel across worker threads; this render-thread hook would
 * serialise all the copy/queue work and add latency. Keep both: worker path handles
 * non-empty (fast, parallel); this render-path catches the synthetic empties the
 * worker path misses.
 *
 * <p>{@code remap = false} per the Sodium-mixin convention.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerEmptyMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/sodium");

    /** Records per-section "first observed" time the moment Sodium creates a RenderSection.
     *  Fires for every section in every chunk MC loads — including all-air ones (the
     *  hasOnlyAir() branch still goes through this method per onChunkAdded's loop).
     *  Mirrors vanilla MC's per-section fade-timer semantics: timer starts when the
     *  section is initialised by the renderer, not when geometry first appears. Block
     *  placements in long-loaded sections are then correctly suppressed (delta from
     *  registration > fadeDuration → no fade), while sections that just streamed in
     *  fade individually based on their own arrival time (not the parent chunk's). */
    @Inject(method = "onSectionAdded", at = @At("HEAD"))
    private void vulkium$noteSectionAdded(int x, int y, int z, CallbackInfo ci) {
        SectionManager.get().noteSectionAdded(x, y, z);
    }

    /** Drops per-section first-seen tracking when Sodium removes a RenderSection
     *  (chunk unload, F3+A). Prevents unbounded growth of sectionFirstSeenMs. */
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void vulkium$noteSectionRemoved(int x, int y, int z, CallbackInfo ci) {
        SectionManager.get().noteSectionRemoved(x, y, z);
    }

    @Inject(method = "applyBuildOutputs", at = @At("HEAD"))
    private void vulkium$catchSyntheticEmpties(ArrayList<BuilderTaskOutput> outputs,
                                                CallbackInfoReturnable<List<?>> cir) {
        if (outputs == null || outputs.isEmpty()) return;
        for (BuilderTaskOutput out : outputs) {
            if (!(out instanceof ChunkBuildOutput cbo)) continue;
            if (cbo.section == null) continue;
            if (cbo.meshes != null && !cbo.meshes.isEmpty()) continue;
            // Empty meshes → either an empty section (synthetic fast path or
            // ChunkBuilderMeshingTask returned no geometry). Either way, vulkium should
            // evict the section. ChunkBuilderMeshingTaskMixin also catches the
            // non-synthetic empty case; the duplicate evict is harmless.
            try {
                long key = cbo.section.getPosition().asLong();
                SectionManager.get().offerFromSodium(key, cbo);
            } catch (Throwable t) {
                LOGGER.error("synthetic-empty eviction route threw", t);
            }
        }
    }
}
