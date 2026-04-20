package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Piggy-back on MC 26.2's {@code LevelExtractor.allChanged()} — the method F3+A's keyboard
 * handler invokes (via {@code KeyboardHandler}) and that resources/world-reload go through —
 * to also flush vulkium's live section table. {@code LevelExtractor} is the new owner of the
 * "reset all chunks" path in 26.2; {@code LevelRenderer.resetLevelRenderData()} also exists
 * but is called from a different code path (scene extraction, not F3+A directly).
 *
 * <p>Queues {@link SectionManager#queueFlushAll()}, which offers one eviction per live key
 * onto the existing ingest queue. The render thread drains it next frame — no cross-thread
 * coordination needed since {@code allChanged} fires on the render thread anyway.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelRendererAllChangedMixin {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void vulkium$flushAllOnLevelExtractorAllChanged(CallbackInfo ci) {
        SectionManager.get().queueFlushAll();
    }
}
