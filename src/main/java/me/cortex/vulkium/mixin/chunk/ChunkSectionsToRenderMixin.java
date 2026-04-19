package me.cortex.vulkium.mixin.chunk;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When vulkium is actively drawing terrain (config.drawTerrain=true and the mod is enabled),
 * cancel Mojang's native terrain render at {@code renderGroup} HEAD so vulkium's mesh-shader
 * draws replace it rather than overlay. Works for the main OPAQUE / CUTOUT / CUTOUT_MIPPED /
 * TRANSLUCENT layer groups that MC rasterizes via its uber-buffer path.
 *
 * <p>Gated on {@link VulkiumConfig#drawTerrain} so users can A/B compare or fall back to
 * vanilla by flipping a single flag.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup",
            at = @At("HEAD"),
            cancellable = true)
    private void vulkium$suppressVanillaTerrain(ChunkSectionLayerGroup group, GpuSampler sampler,
                                                CallbackInfo ci) {
        if (Vulkium.isEnabled() && VulkiumConfig.get().drawTerrain) {
            ci.cancel();
        }
    }
}
