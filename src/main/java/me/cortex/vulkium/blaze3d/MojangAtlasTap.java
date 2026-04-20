package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Accessor to Mojang's block atlas ({@code minecraft:textures/atlas/blocks}) VK image view so
 * vulkium can bind it as the {@code tex_diffuse} combined-image-sampler at draw time.
 *
 * <p>The view is Mojang-owned; we just call {@link VulkanGpuTextureView#vkImageView()} (public)
 * each frame. The sampler is vulkium-owned — {@link VulkanGpuSampler}'s {@code vkSampler}
 * field is private, so we create and cache our own nearest-mipmap sampler matching the
 * mipmapping behavior vanilla MC uses for terrain.
 *
 * <p>Does not resolve {@code tex_light} yet — the lightmap has a different owner path in
 * MC 26.2 (not under AtlasManager). That's a follow-up.
 */
public final class MojangAtlasTap {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/atlas");

    private static long cachedSampler = VK10.VK_NULL_HANDLE;
    private static int cachedMipLevels = -1;      // atlas mip count the cached sampler was built for
    private static int cachedOptionMipmap = -1;   // MC's Options.mipmapLevels().get() at cache time
    private static boolean samplerLogged;

    private MojangAtlasTap() {}

    /**
     * @return the {@code VkImageView} handle of Mojang's block atlas texture, or
     *         {@code VK_NULL_HANDLE} if the atlas isn't ready or not backed by Vulkan.
     */
    /** Look up the block-atlas TextureAtlas via AtlasManager.forEach, matching
     *  {@code atlas.location() == TextureAtlas.LOCATION_BLOCKS}. AtlasManager.getAtlasOrThrow
     *  in MC 26.2 uses the ATLAS id (e.g. "minecraft:blocks"), not the texture location. */
    private static TextureAtlas findBlockAtlas() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        final TextureAtlas[] found = new TextureAtlas[1];
        try {
            mc.getAtlasManager().forEach((id, atlas) -> {
                if (found[0] != null) return;
                if (TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
                    found[0] = atlas;
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
        return found[0];
    }

    public static long blockAtlasImageView() {
        TextureAtlas atlas = findBlockAtlas();
        if (atlas == null) return VK10.VK_NULL_HANDLE;
        GpuTextureView view = atlas.getTextureView();
        if (!(view instanceof VulkanGpuTextureView vkv)) return VK10.VK_NULL_HANDLE;
        return vkv.vkImageView();
    }

    public static long blockAtlasImage() {
        TextureAtlas atlas = findBlockAtlas();
        if (atlas == null) return VK10.VK_NULL_HANDLE;
        GpuTextureView view = atlas.getTextureView();
        if (!(view instanceof VulkanGpuTextureView vkv)) return VK10.VK_NULL_HANDLE;
        return vkv.texture().vkImage();
    }

    public static int blockAtlasVkFormat() {
        TextureAtlas atlas = findBlockAtlas();
        if (atlas == null) return 0;
        GpuTextureView view = atlas.getTextureView();
        if (!(view instanceof VulkanGpuTextureView vkv)) return 0;
        return com.mojang.blaze3d.vulkan.VulkanConst.toVk(vkv.texture().getFormat());
    }

    public static int blockAtlasMipLevels() {
        TextureAtlas atlas = findBlockAtlas();
        if (atlas == null) return 1;
        GpuTextureView view = atlas.getTextureView();
        if (!(view instanceof VulkanGpuTextureView vkv)) return 1;
        return Math.max(1, vkv.texture().getMipLevels());
    }

    /**
     * Returns a vulkium-owned sampler configured to match vanilla MC's terrain sampler:
     * nearest mag/min, linear mipmap filter between levels, clamp-to-edge, anisotropy off.
     *
     * <p>The sampler's max-LOD is driven by two inputs: the atlas's actual mip count (from
     * {@link #blockAtlasMipLevels()}) and the user's {@code Video Settings → Mipmap Levels}
     * option (0–4). {@code maxLod = min(atlasMips - 1, optionLevels)}. When the user changes
     * the vanilla mipmap slider MC rebuilds the atlas with a new mip count — we notice the
     * change here and re-create the sampler to match.
     *
     * <p>Cached across frames; invalidated when either the atlas mip count or the option
     * changes. First call or rebuild logs once.
     */
    public static long sampler() {
        int atlasMips = Math.max(1, blockAtlasMipLevels());
        int optionMips = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                Object raw = mc.options.mipmapLevels().get();
                if (raw instanceof Integer i) optionMips = i;
            }
        } catch (RuntimeException ignored) {
            // MC not ready yet — fall back to no mipmapping (optionMips=0).
        }
        if (cachedSampler != VK10.VK_NULL_HANDLE
                && cachedMipLevels == atlasMips
                && cachedOptionMipmap == optionMips) {
            return cachedSampler;
        }
        // Rebuild: destroy old first.
        if (cachedSampler != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySampler(MojangVulkanBridge.vkDevice(), cachedSampler, null);
            cachedSampler = VK10.VK_NULL_HANDLE;
        }
        // Max LOD = min(atlasMips - 1, optionMips). optionMips==0 → maxLod=0 (no mipmapping).
        final int effectiveMaxMip = Math.max(0, Math.min(atlasMips - 1, optionMips));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST)
                .minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod((float) effectiveMaxMip).mipLodBias(0f);
            LongBuffer pSampler = stack.callocLong(1);
            int r = VK10.vkCreateSampler(MojangVulkanBridge.vkDevice(), info, null, pSampler);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler (atlas) failed: VkResult=" + r);
            }
            cachedSampler = pSampler.get(0);
            cachedMipLevels = atlasMips;
            cachedOptionMipmap = optionMips;
            LOGGER.info("{} vulkium block-atlas sampler (handle=0x{}, atlasMips={}, optionMips={}, maxLod={}).",
                samplerLogged ? "Rebuilt" : "Created",
                Long.toHexString(cachedSampler), atlasMips, optionMips, effectiveMaxMip);
            samplerLogged = true;
            return cachedSampler;
        }
    }

    /** Destroy the cached sampler. Call from shutdown before VulkanDevice teardown. */
    public static void shutdown() {
        if (cachedSampler != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySampler(MojangVulkanBridge.vkDevice(), cachedSampler, null);
            cachedSampler = VK10.VK_NULL_HANDLE;
        }
    }
}
