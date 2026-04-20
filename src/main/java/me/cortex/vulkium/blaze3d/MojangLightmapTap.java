package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Accessor for MC's {@code Lightmap} texture (the 16×16 R8G8B8A8 image that encodes
 * block-light × sky-light → colour). Vulkium binds this to the fragment shader's
 * {@code tex_light} sampler so terrain reacts to torch illumination and day/night cycle.
 *
 * <p>The lightmap is GameRenderer-owned; access-widener on the {@code lightmap} field.
 */
public final class MojangLightmapTap {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/lightmap");
    private static long cachedSampler = VK10.VK_NULL_HANDLE;

    private MojangLightmapTap() {}

    public static long lightmapImageView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return VK10.VK_NULL_HANDLE;
        Lightmap lm = ((GameRenderer) mc.gameRenderer).lightmap;
        if (lm == null) return VK10.VK_NULL_HANDLE;
        GpuTextureView view = lm.getTextureView();
        if (!(view instanceof VulkanGpuTextureView vkv)) return VK10.VK_NULL_HANDLE;
        return vkv.vkImageView();
    }

    /** Linear-filter sampler with CLAMP_TO_EDGE — lightmap gradient should interpolate, not
     *  pixelate. 1-mip, no anisotropy. */
    public static long sampler() {
        if (cachedSampler != VK10.VK_NULL_HANDLE) return cachedSampler;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR)
                .minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod(0f).mipLodBias(0f);
            LongBuffer pSampler = stack.callocLong(1);
            int r = VK10.vkCreateSampler(MojangVulkanBridge.vkDevice(), info, null, pSampler);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler (lightmap) failed: VkResult=" + r);
            }
            cachedSampler = pSampler.get(0);
            LOGGER.info("Created vulkium lightmap sampler (handle=0x{}).",
                Long.toHexString(cachedSampler));
            return cachedSampler;
        }
    }
}
