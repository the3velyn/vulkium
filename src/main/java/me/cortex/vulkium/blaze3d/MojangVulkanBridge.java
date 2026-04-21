package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Single contact surface with Mojang's Vulkan backend.
 *
 * <p>MC 26.1+ is deobfuscated and exposes {@code VulkanDevice} publicly. We do not need any
 * mixin to extract VK handles — we just cast {@code GpuDevice.backend} (field exposed via our
 * accesswidener). Per-frame ordering is driven by Fabric's {@code LevelRenderEvents} in
 * {@link me.cortex.vulkium.render.FrameDriver}; command-buffer interception mixins (if any
 * are needed) live under {@code me.cortex.vulkium.mixin.blaze3d}.
 */
public final class MojangVulkanBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/blaze3d");

    /**
     * Populated by {@code VulkanDeviceMixin} when Mojang constructs its {@code VulkanDevice}.
     * Mojang does not hold a reference to the physical device after construction, so this mixin
     * is the only way to learn which {@code VkPhysicalDevice} backs the current device.
     */
    private static volatile @Nullable VulkanPhysicalDevice capturedPhysicalDevice;
    private static volatile Set<String> enabledDeviceExtensions = Collections.emptySet();

    private MojangVulkanBridge() {}

    /** Called from {@code VulkanDeviceMixin.<init>@TAIL}. */
    public static void installPhysicalDeviceContext(VulkanPhysicalDevice physicalDevice,
                                                     Set<String> enabledExtensions) {
        capturedPhysicalDevice = physicalDevice;
        enabledDeviceExtensions = Set.copyOf(enabledExtensions);
    }

    @Nullable
    public static VulkanDevice vulkanDevice() {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) return null;
        // `backend` is private in GpuDevice; exposed via vulkium.accesswidener.
        if (device.backend instanceof VulkanDevice vk) return vk;
        return null;
    }

    public static boolean active() {
        return vulkanDevice() != null;
    }

    public static VkDevice vkDevice()   { return require().vkDevice(); }
    public static VkInstance vkInstance() { return require().instance().vkInstance(); }
    public static VulkanInstance vulkanInstance() { return require().instance(); }

    /**
     * @return the {@code VulkanPhysicalDevice} captured by {@code VulkanDeviceMixin}, or
     *     {@code null} if the mixin has not yet fired (i.e., before Mojang's backend creation).
     */
    @Nullable
    public static VulkanPhysicalDevice vulkanPhysicalDevice() { return capturedPhysicalDevice; }

    /** @return raw {@code VkPhysicalDevice} if available; otherwise null. */
    @Nullable
    public static VkPhysicalDevice vkPhysicalDevice() {
        VulkanPhysicalDevice p = capturedPhysicalDevice;
        return p == null ? null : p.vkPhysicalDevice();
    }

    /** @return the set of device extensions Mojang enabled on creation (from the mixin). */
    public static Set<String> enabledDeviceExtensions() { return enabledDeviceExtensions; }

    public static VulkanQueue graphicsQueue() { return require().graphicsQueue(); }
    public static VulkanQueue computeQueue()  { return require().computeQueue(); }
    public static VulkanQueue transferQueue() { return require().transferQueue(); }
    public static VkQueue vkGraphicsQueue() { return require().graphicsQueue().vkQueue(); }
    public static int graphicsQueueFamilyIndex() { return require().graphicsQueue().queueFamilyIndex(); }

    /** @return Mojang's pre-existing VMA allocator handle. Vulkium piggy-backs on it. */
    public static long vma() { return require().vma(); }

    /**
     * Mojang's shared per-device command encoder — despite the {@code create} name this
     * returns the same encoder instance each call, backed by the single field on
     * {@code VulkanDevice}. Use it to record command buffers that must land in the same
     * queue submission as Mojang's own frame commands.
     */
    public static VulkanCommandEncoder commandEncoder() {
        return require().createCommandEncoder();
    }

    /** @deprecated misleading name; use {@link #commandEncoder()} — the encoder is shared. */
    @Deprecated
    public static VulkanCommandEncoder newCommandEncoder() {
        return commandEncoder();
    }

    public static void logBackendInfo() {
        VulkanDevice vk = vulkanDevice();
        if (vk == null) {
            LOGGER.info("Mojang backend = OpenGL. Vulkium will remain dormant (no mesh-shader acceleration possible on the GL backend).");
            return;
        }
        LOGGER.info("Mojang backend = Vulkan. Device: {}", vk.getDeviceInfo());
        LOGGER.info("Vulkium bridge hooks: VkDevice={}, VkInstance={}, graphicsQueue.family={}, VMA={}",
            vk.vkDevice(), vk.instance().vkInstance(), vk.graphicsQueue().queueFamilyIndex(), vk.vma());
    }

    private static VulkanDevice require() {
        VulkanDevice d = vulkanDevice();
        if (d == null) {
            throw new IllegalStateException(
                "MojangVulkanBridge accessed outside the Vulkan backend. " +
                "Vulkium gates every VK access on isEnabled(); calling this when disabled is a bug.");
        }
        return d;
    }
}
