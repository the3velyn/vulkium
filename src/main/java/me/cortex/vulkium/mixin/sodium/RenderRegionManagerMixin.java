package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.Vulkium;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Sodium's GPU upload of compiled section meshes. With vulkium owning the terrain
 * draw, every byte Sodium would push to its per-region {@code DeviceResources} (geometry
 * arena + index arena) is dead weight — vulkium already captured the same bytes from
 * {@code ChunkBuilderMeshingTaskMixin} and copied them into its own arena.
 *
 * <p>Why HEAD-cancel is safe:
 * <ul>
 *   <li>{@code processChunkBuilds}, which calls us, still runs {@code result.destroy()} on
 *       every output afterward — so Sodium's {@code NativeBuffer} (host RAM) cleanup is
 *       unaffected.</li>
 *   <li>Region {@code DeviceResources} are allocated lazily inside the cancelled body
 *       (via {@code region.createStorage(pass)}). Cancel = no GPU geometry/index buffer
 *       ever instantiated for any region. That's the entire 4 GiB-scale VRAM saving on
 *       the sodium edition.</li>
 *   <li>{@code DefaultChunkRenderer.render} (cancelled separately by
 *       {@link DefaultChunkRendererMixin}) tolerates {@code storage == null} with a
 *       {@code continue} — even without our draw-side cancel, no Sodium draw would
 *       execute against missing storage.</li>
 *   <li>Sort/translucent index state goes unmaintained, but Sodium's own draw path is
 *       cancelled — nobody reads it.</li>
 * </ul>
 *
 * <p>Trade-off: toggling Vulkium off mid-session leaves Sodium with empty storages
 * (terrain disappears) until something triggers a full chunk rebuild (F3+A, world
 * rejoin). Documented as a known limitation; matches the existing standalone-edition
 * toggle-recovery cost.
 *
 * <p>Note on the method descriptor: {@code UniformBufferManager} moved between Sodium dev
 * ({@code ...chunk.compile.UniformBufferManager}) and the 0.9.1-beta.2 release jar
 * ({@code ...chunk.UniformBufferManager}). We compile against the release jar — the
 * descriptor below uses the release path. A future Sodium bump that moves the class
 * back will fail with a clear "no targets matching" error.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class RenderRegionManagerMixin {

    @Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkium$cancelUpload(CallbackInfo ci) {
        if (Vulkium.isEnabled()) {
            ci.cancel();
        }
    }
}
