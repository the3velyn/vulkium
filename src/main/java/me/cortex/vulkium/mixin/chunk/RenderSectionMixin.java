package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges MC's RenderSection lifecycle into vulkium's live-section table.
 *
 * <p>{@code setSectionNode(long)} is called every time a RenderSection moves in MC's rotating
 * section storage — effectively an eviction (old section) + activation (new section). We use
 * it to tell {@link SectionManager} the old section's data should be dropped so the live
 * table doesn't grow unboundedly as the player moves.
 *
 * <p>{@code reset()} is called when a RenderSection leaves the cache entirely.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {

    @Shadow
    private volatile long sectionNode;

    @Inject(method = "setSectionNode(J)V",
            at = @At("HEAD"))
    private void vulkium$onSectionMoved(long newNode, CallbackInfo ci) {
        if (!Vulkium.isEnabled()) return;
        long old = this.sectionNode;
        if (old != newNode && old != 0L) {
            SectionManager.get().evict(old);
        }
    }

    @Inject(method = "reset()V",
            at = @At("HEAD"))
    private void vulkium$onReset(CallbackInfo ci) {
        if (!Vulkium.isEnabled()) return;
        long old = this.sectionNode;
        if (old != 0L) {
            SectionManager.get().evict(old);
        }
    }
}
