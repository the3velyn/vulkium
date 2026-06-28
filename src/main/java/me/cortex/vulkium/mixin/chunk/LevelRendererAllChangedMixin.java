package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Piggy-back on MC 26.2's {@code LevelExtractor.allChanged()} — the method F3+A's keyboard
 * handler invokes (via {@code KeyboardHandler}), world rejoin goes through, and resource
 * reload triggers — to flush vulkium's live section table so MC's subsequent recompile
 * fills it back up with fresh per-section data.
 *
 * <h3>Soft reset only — no Renderer.shutdown()</h3>
 * <p>An earlier version of this mixin called {@code Renderer.get().shutdown()} on every
 * allChanged because in-session F3+A (and world rejoin) had a 5-second VK semaphore
 * timeout on the first frame after a section flush. Root cause was the
 * {@code UploadStream}'s {@code flushLo[]} default-zero initialization (constructor only
 * reset slot 0): the first commitFrame stamped every section with sig=1, then
 * advanceSection deadlocked waiting on a value Mojang couldn't signal (we were inside
 * the render scope). Combined with {@code VkSemaphoreWaitInfo.semaphoreCount} not being
 * auto-populated by LWJGL's setters, the wait blocked for the full 5-second timeout.
 *
 * <p>Both bugs are fixed (commit on this branch). The full teardown was a workaround for
 * that deadlock — it's no longer required, and it cost ~350ms of shader recompile +
 * 4GB arena reallocation + vkDeviceWaitIdle on every F3+A press. Now we only clear
 * section data; pipelines, arena, HZB, samplers, and the upload ring stay live and
 * ready to accept the next batch of section captures.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelRendererAllChangedMixin {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void vulkium$flushAllOnLevelExtractorAllChanged(CallbackInfo ci) {
        // Soft reset: clear vulkium's section state, leave VK resources alone. The arena
        // segments held by current live entries get freed inside evictLive →
        // uploader.releaseSection, the region ledger gets cleared via
        // regionManager.removeSection, and the ingest/retry/resort queues are emptied.
        // Next frame's compile workers fill it back up with fresh data.
        SectionManager.get().queueFlushAll();
        // NO eager levelRenderer.resetLevelRenderData() in 26.2-pre-2 — it breaks recompile.
        //
        // History: this was added because older MC's allChanged() only updated
        // SectionUpdateTracker + lastViewDistance, so ViewArea wasn't rebuilt and an enlarged
        // RD slider wouldn't extend the visible grid until a manual reload. We force-reset
        // here to make F3+A (and other allChanged paths) also rebuild ViewArea.
        //
        // In 26.2-pre-2, allChanged() now sets shouldInvalidateCompiledGeometry=true, and the
        // next-frame LevelExtractor.extract calls LevelRenderer.invalidateCompiledGeometry(),
        // which itself creates a fresh ViewArea and reconnects SectionOcclusionGraph via
        // waitAndReset(newViewArea). So MC already does the rebuild for us — our eager
        // resetLevelRenderData() is now redundant.
        //
        // Worse, it's actively harmful: resetLevelRenderData() nulls viewArea and calls
        // SectionOcclusionGraph.waitAndReset(null). The graph's worker can't recover from a
        // null-target reset, so even though invalidateCompiledGeometry rebinds it to a new
        // ViewArea afterward, the graph never produces visibleSections — so no sections ever
        // get marked dirty, MC's compile workers go idle, and terrain never returns after
        // F3+A or the in-game vulkium toggle (which also fires allChanged()). 27-second
        // observation with seq%128 capture logging: zero captures post-F3+A, confirmed in
        // the repro log on 2026-06-01.
    }
}
