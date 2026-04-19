package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

/**
 * Hierarchical-Z buffer texture — r32f, one view per mip for storage writes, one combined
 * sampled view for reads. Paired with {@link HzbDownsample} to build the mip chain each frame
 * from a depth source (mip 0 populated externally from Mojang's depth attachment).
 *
 * <p>Conservative occlusion: each mip holds the max (farthest) depth of its 2×2 source block.
 * When testing a region/section AABB, callers pick the mip level whose texel size covers the
 * AABB's screen-space extent and compare its occluder depth against the AABB's near Z.
 */
public final class HzbTexture implements AutoCloseable {

    public static final int FORMAT = VK10.VK_FORMAT_R32_SFLOAT;

    private final int width;
    private final int height;
    private final int mipLevels;
    private final long image;
    private final long allocation;
    private final long sampledView;       // full-mip-range view for sampling
    private final long[] storageViews;    // one per mip, for writeonly storage image
    private final long sampler;           // nearest-clamp for point-sampling during HZB build + tests

    private boolean closed;

    private HzbTexture(int w, int h, int mips, long image, long alloc, long sampledView,
                       long[] storageViews, long sampler) {
        this.width = w;
        this.height = h;
        this.mipLevels = mips;
        this.image = image;
        this.allocation = alloc;
        this.sampledView = sampledView;
        this.storageViews = storageViews;
        this.sampler = sampler;
    }

    public static HzbTexture allocate(int width, int height) {
        int mips = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        long vma = MojangVulkanBridge.vma();
        long device = MojangVulkanBridge.vkDevice().address();
        var vkDevice = MojangVulkanBridge.vkDevice();

        long image;
        long alloc;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(FORMAT)
                .mipLevels(mips)
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(width, height, 1);

            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO);

            LongBuffer pImage = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);
            int r = Vma.vmaCreateImage(vma, ici, aci, pImage, pAlloc, null);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vmaCreateImage (HZB) failed: VkResult=" + r);
            }
            image = pImage.get(0);
            alloc = pAlloc.get(0);
        }

        long[] storageViews = new long[mips];
        long sampledView;
        long sampler;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .format(FORMAT);
            vci.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseArrayLayer(0)
                .layerCount(1);

            // Sampled view: all mips
            vci.subresourceRange().baseMipLevel(0).levelCount(mips);
            LongBuffer pView = stack.callocLong(1);
            int r = VK10.vkCreateImageView(vkDevice, vci, null, pView);
            if (r != VK10.VK_SUCCESS) throw new RuntimeException("vkCreateImageView (sampled) failed: " + r);
            sampledView = pView.get(0);

            // One storage view per mip
            for (int i = 0; i < mips; i++) {
                vci.subresourceRange().baseMipLevel(i).levelCount(1);
                r = VK10.vkCreateImageView(vkDevice, vci, null, pView);
                if (r != VK10.VK_SUCCESS) {
                    throw new RuntimeException("vkCreateImageView (storage mip " + i + ") failed: " + r);
                }
                storageViews[i] = pView.get(0);
            }

            // Sampler — nearest, clamp-to-edge, no mipmap interpolation (we pick the exact mip).
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack)
                .sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST)
                .minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod((float) mips);
            LongBuffer pSampler = stack.callocLong(1);
            r = VK10.vkCreateSampler(vkDevice, sci, null, pSampler);
            if (r != VK10.VK_SUCCESS) throw new RuntimeException("vkCreateSampler (HZB) failed: " + r);
            sampler = pSampler.get(0);
        }

        return new HzbTexture(width, height, mips, image, alloc, sampledView, storageViews, sampler);
    }

    public int width()        { return width; }
    public int height()       { return height; }
    public int mipLevels()    { return mipLevels; }
    public long image()       { return image; }
    public long sampledView() { return sampledView; }
    public long sampler()     { return sampler; }
    public long storageView(int mip) {
        if (mip < 0 || mip >= mipLevels) throw new IndexOutOfBoundsException("mip " + mip);
        return storageViews[mip];
    }

    /** Width of the given mip level (min 1). */
    public int mipWidth(int mip)  { return Math.max(1, width  >> mip); }
    /** Height of the given mip level (min 1). */
    public int mipHeight(int mip) { return Math.max(1, height >> mip); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var vkDevice = MojangVulkanBridge.vkDevice();
        VK10.vkDestroySampler(vkDevice, sampler, (org.lwjgl.vulkan.VkAllocationCallbacks) null);
        VK10.vkDestroyImageView(vkDevice, sampledView, (org.lwjgl.vulkan.VkAllocationCallbacks) null);
        for (long v : storageViews) {
            if (v != 0L) VK10.vkDestroyImageView(vkDevice, v, (org.lwjgl.vulkan.VkAllocationCallbacks) null);
        }
        Vma.vmaDestroyImage(MojangVulkanBridge.vma(), image, allocation);
    }
}
