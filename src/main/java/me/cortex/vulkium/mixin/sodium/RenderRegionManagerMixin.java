package me.cortex.vulkium.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observation-only hook on Sodium's region-upload path. Fires once per
 * {@code RenderRegionManager#uploadResults} call (typically once per frame when there are
 * compiled sections to upload to GPU buffers).
 *
 * <p>This is where the eventual vulkium ingest hand-off will live: instead of (or in addition
 * to) letting Sodium upload the meshes into its own GL/Vulkan region buffers, route the same
 * results into vulkium's SectionManager + TerrainUploader so our mesh-shader pipeline gets
 * them. For now it just counts invocations so we can validate the hook fires.
 *
 * <p>{@code remap = false} per Sodium's mapping convention.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class RenderRegionManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/sodium");
    private static final AtomicLong UPLOAD_CALLS = new AtomicLong();

    // UniformBufferManager moved between Sodium dev (`...chunk.compile.UniformBufferManager`)
    // and the 0.9.1-beta.2 release jar (`...chunk.UniformBufferManager`). We compile against
    // the release jar, so the method descriptor here uses the .chunk.* path. If we later bump
    // to a Sodium where this moves back, the mixin processor will fail with a clear
    // "could not find any targets matching" error pointing here.
    @Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD"))
    private void vulkium$observeUploadResults(CallbackInfo ci) {
        long n = UPLOAD_CALLS.incrementAndGet();
        if (n <= 4 || n % 256 == 0) {
            LOGGER.info("Observed Sodium uploadResults call #{}", n);
        }
    }
}
