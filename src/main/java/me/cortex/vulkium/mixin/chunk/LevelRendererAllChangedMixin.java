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
 * reload triggers — to flush vulkium's live section table AND fully reset the Renderer's
 * VK subsystem. {@code LevelExtractor} is the new owner of the "reset all chunks" path in
 * 26.2; {@code LevelRenderer.resetLevelRenderData()} also exists but is called from a
 * different code path (scene extraction, not F3+A directly).
 *
 * <h3>Why a full Renderer teardown here</h3>
 * <p>Queueing {@link SectionManager#queueFlushAll()} alone was enough for in-session F3+A,
 * but world REJOIN (exit to menu → re-enter) hit a 5s VK semaphore timeout on the first
 * frame after the flush. The prior session's GPU buffers (arena, visibility, HZB,
 * dispatch-list ring slots) all persist because Renderer is a singleton and we don't call
 * shutdown on world exit. The first frame of the new world then mixes fresh MC state
 * (new render target, refreshed atlas, fresh depth attachment) with our stale GPU-side
 * buffers — one of those interactions deadlocks the NVIDIA Windows driver.
 *
 * <p>Renderer.shutdown() + next-frame ensureInit is the known-clean pattern from the
 * master-toggle flow ({@link me.cortex.vulkium.gui.VulkiumOptionsScreen}). Applying it
 * here too costs a ~1-2 frame stall plus arena reallocation, which is negligible during
 * a world-change event that's already an I/O-dominated stall on MC's side.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelRendererAllChangedMixin {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void vulkium$flushAllOnLevelExtractorAllChanged(CallbackInfo ci) {
        // Full renderer teardown mirrors the master-toggle path. shutdown() gates its
        // vkDeviceWaitIdle on there being live resources, so F3+A in-session (where the
        // renderer is already live) pays the wait; world-change at a moment the renderer
        // has already shut down pays nothing extra. The next prepareFrame() runs
        // ensureInit() and rebuilds all VK subsystems from scratch, giving us clean state
        // that matches fresh MC handles — no stale arena data, no stale visibility bits,
        // no stale push-descriptor-target images.
        try {
            me.cortex.vulkium.render.Renderer.get().shutdown();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("vulkium/world-change")
                .warn("Renderer shutdown on allChanged failed", t);
        }
        SectionManager.get().queueFlushAll();
        // Force LevelRenderer.resetLevelRenderData so ViewArea gets rebuilt at the current RD.
        // MC's LevelExtractor.allChanged only updates SectionUpdateTracker + lastViewDistance;
        // ViewArea itself is only rebuilt from inside LevelExtractor.extract (gated on
        // shouldResetLevelRenderData, which is only set by resource-reload). This means raising
        // the RD slider past the initial value never extends the visible grid until the user
        // reloads chunks or resources. Piggy-backing the full reset here makes F3+A (and other
        // allChanged paths) also expand the grid when RD has grown.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.levelRenderer != null) {
            mc.levelRenderer.resetLevelRenderData();
        }
    }
}
