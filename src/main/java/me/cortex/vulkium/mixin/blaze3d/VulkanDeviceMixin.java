package me.cortex.vulkium.mixin.blaze3d;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Captures the {@code VulkanPhysicalDevice} parameter at {@code VulkanDevice} construction
 * time so {@link MojangVulkanBridge} can expose it for feature probing.
 *
 * <p>Mojang's public API only exposes {@code VulkanDevice.vkDevice()}, {@code .instance()}, and
 * the queue accessors — not the physical device that was used to build it. We need the physical
 * device to call {@code vkGetPhysicalDeviceFeatures2} / {@code vkEnumerateDeviceExtensionProperties}
 * for the Turing+ gate ({@code VK_EXT_mesh_shader}, buffer-device-address, etc.).
 */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkium$captureHandles(
        ShaderSource shaderSource,
        VulkanInstance instance,
        VulkanPhysicalDevice physicalDevice,
        Set<String> enabledDeviceExtensions,
        VkDevice vkDevice,
        long vma,
        CallbackInfo ci
    ) {
        MojangVulkanBridge.installPhysicalDeviceContext(physicalDevice, enabledDeviceExtensions);
    }
}
