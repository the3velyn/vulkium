package me.cortex.vulkium.mixin.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds {@code VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT} to Mojang's VMA allocator creation
 * so buffer-device-address allocations (needed by vulkium's buffer-reference shader model) can
 * be created through the shared allocator. Without this flag, VMA rejects any buffer with
 * {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT} with {@code VK_ERROR_INITIALIZATION_FAILED}
 * even if the VkDevice itself has the feature enabled.
 *
 * <p>Pairs with {@link me.cortex.vulkium.blaze3d.MojangBackendFixup} which injects the feature
 * itself into Mojang's VkDevice creation — that handles enablement at the device level; this
 * handles enablement at the allocator level.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

    // `static` because MC 26.2-snapshot-4 made {@code VulkanBackend.createVma} static — Mixin
    // requires redirect-callback statics to mirror their enclosing method's static-ness, and
    // rejects the mixin with "non-static callback targets a static method" otherwise. Harmless
    // on snapshot-3 where createVma is instance — a static callback on an instance target
    // method also works; Mixin's rule is one-way (static target → static callback required).
    @Redirect(method = "createVma",
              at = @At(value = "INVOKE",
                       target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"))
    private static int vulkium$addDeviceAddressFlag(VmaAllocatorCreateInfo info, PointerBuffer pAllocator) {
        info.flags(info.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        return Vma.vmaCreateAllocator(info, pAllocator);
    }
}
