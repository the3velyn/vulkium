package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Piggy-back on MC's {@code LevelRenderer.allChanged()} — the method called by F3+A and on
 * world reload — to also flush vulkium's live section table. Without this, hitting F3+A
 * cleared MC's render state but vulkium kept its arena entries; the next set of captures
 * from MC's recompile would see {@code live.put} as a same-key update (freeing old +
 * installing new), so the end result was mostly fine but memory-wasteful in the interim
 * and kept stale sections if MC didn't recompile them.
 *
 * <p>Queues {@link SectionManager#queueFlushAll()}, which offers one eviction per live key
 * onto the existing ingest queue. The render thread drains it next frame — no cross-thread
 * coordination needed since MC's {@code allChanged} fires on the render thread anyway.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererAllChangedMixin {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void vulkium$flushAllOnAllChanged(CallbackInfo ci) {
        SectionManager.get().queueFlushAll();
    }
}
