package me.cortex.vulkium.mixin.chunk;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/chunk-tap");

    @Shadow public abstract GpuTextureView textureView();


    @Inject(method = "renderGroup",
            at = @At("HEAD"),
            cancellable = true)
    private void vulkium$suppressVanillaTerrain(ChunkSectionLayerGroup group, GpuSampler sampler,
                                                CallbackInfo ci) {
        // Tap Mojang's color-attachment format on first call so FrameDriver can build the
        // SecondaryRecorder.InheritanceSpec with the real format (not a guess). Failure to match
        // silently makes our secondary's draws produce nothing.
        if (me.cortex.vulkium.blaze3d.MojangColorFormat.get() == 0) {
            try {
                GpuTextureView view = textureView();
                if (view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView vkView
                    && vkView.texture() != null) {
                    int vk = VulkanConst.toVk(vkView.texture().getFormat());
                    int w = vkView.texture().getWidth(0);
                    int h = vkView.texture().getHeight(0);
                    long imageView = vkView.vkImageView();
                    me.cortex.vulkium.blaze3d.MojangColorFormat.set(vk, w, h, imageView);
                    LOGGER.info("Captured Mojang color attachment VkFormat={} ({}x{}, layer group={}, view=0x{})",
                        vk, w, h, group, Long.toHexString(imageView));
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to capture Mojang color format", t);
            }
        }

        if (Vulkium.isEnabled() && VulkiumConfig.get().drawTerrain) {
            ci.cancel();
        }
    }
}
