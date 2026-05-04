package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Injects vulkium's required extensions + features into Mojang's static
 * {@code VulkanBackend.REQUIRED_DEVICE_EXTENSIONS} and {@code REQUIRED_DEVICE_FEATURES} BEFORE
 * Mojang constructs its backend. This is why our probe was reporting "VK_EXT_mesh_shader not
 * exposed" on hardware that clearly supports it — Mojang had no reason to enable the extension,
 * so the device was created without it.
 *
 * <p>Must be called from {@code onInitializeClient} (client entry, pre-backend).
 *
 * <p>Two things to inject:
 * <ol>
 *   <li>{@code VK_EXT_mesh_shader} into the enabled-extensions list.</li>
 *   <li>A {@link VulkanFeature} entry for {@code meshShader} + {@code meshShaderQueries} so
 *       Mojang's iteration of {@code REQUIRED_DEVICE_FEATURES} appends our
 *       {@code VkPhysicalDeviceMeshShaderFeaturesEXT} with the right bits set to the pNext
 *       chain of the {@code vkCreateDevice} call.</li>
 *   <li>{@code bufferDeviceAddress} (the Vulkan 1.2 feature) — Mojang may already include it,
 *       but we add our own entry defensively. Duplicates are harmless (Mojang sets the same
 *       bit twice in the loop).</li>
 * </ol>
 *
 * <p>The sets are wrapped by {@link Set#of Set.of(...)} in Mojang source (immutable), so
 * {@code set.add(...)} would throw. We detect that and fall back to reflecting a new mutable
 * {@link HashSet} containing the originals + our additions, writing it back through the
 * accesswidener-opened field.
 */
public final class MojangBackendFixup {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/backend-fixup");

    private static boolean applied;

    private MojangBackendFixup() {}

    public static void apply() {
        if (applied) return;
        applied = true;

        // CRITICAL: injecting a required extension that no physical device supports makes
        // Mojang's findPhysicalDevice() fail, which crashes MC's Vulkan init. Pre-probe with
        // our own throwaway VkInstance to check that at least one device actually supports
        // VK_EXT_mesh_shader. If none do, skip the injection entirely and let Mojang boot
        // normally (vulkium will self-disable downstream).
        if (!anyDeviceSupportsMeshShader()) {
            LOGGER.info("No Vulkan-capable physical device on this machine exposes "
                + "VK_EXT_mesh_shader. Skipping backend fixup; vulkium will be dormant.");
            return;
        }

        injectExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);

        VulkanPNextStruct meshStruct = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
            VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF);
        injectFeature(new VulkanFeature(meshStruct, "meshShader",
            VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER));
        injectFeature(new VulkanFeature(meshStruct, "meshShaderQueries",
            VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADERQUERIES));
        // taskShader — without this feature bit enabled at device creation, EmitMeshTasksEXT
        // in the task stage silently emits nothing, so no mesh workgroups get dispatched even
        // though vkCmdDrawMeshTasksEXT looks like it succeeded. Our terrain pipeline has a
        // task stage so this is required for anything to actually draw.
        injectFeature(new VulkanFeature(meshStruct, "taskShader",
            VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER));

        // bufferDeviceAddress — vulkium needs it for buffer-reference shader access. Reuse
        // Mojang's existing VK12_FEATURES_STRUCT so we share the same pNext-chain entry Mojang
        // already manages. NVIDIA drivers read bufferDeviceAddress from
        // VkPhysicalDeviceVulkan12Features and ignore the older standalone extension-struct
        // form — so using VK12_FEATURES_STRUCT is what actually takes effect.
        injectFeature(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));

        // storageBuffer8BitAccess — needed for well-defined byte-granularity writes to the
        // regionVisibility / sectionVisibility storage buffers from the cull shaders. Without
        // it, NVIDIA (and other drivers) lower uint8_t SSBO stores to non-atomic 32-bit RMW,
        // so 4 adjacent threads within a compute workgroup race each other on the same word
        // and one of the four bytes' writes wins. Symptom: sporadic single-section false-occlusion
        // producing vertical sky slivers where one section's visibility bit got clobbered
        // (observed 2026-04-21 in the W screenshot of the deep-test run). Promoted to core in
        // Vulkan 1.2 so the feature toggle alone is sufficient — no separate extension name
        // needed (KHR_8bit_storage is the pre-promotion name).
        injectFeature(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "storageBuffer8BitAccess",
            VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS));

        // depthClamp — vulkium uses MC's unmodified projection (so depth values match
        // MC's entities exactly) but enables depthClampEnable=true on its mesh-shader
        // pipeline, so vertices past MC's finite far plane get their depth clamped to
        // maxDepth instead of being clipped. Without this device feature the pipeline
        // create call rejects depthClampEnable=true and our terrain disappears past
        // ~2048 blocks again. Core 1.0 feature, lives in VkPhysicalDeviceFeatures.
        injectFeature(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "depthClamp",
            org.lwjgl.vulkan.VkPhysicalDeviceFeatures.DEPTHCLAMP));

        LOGGER.info("Injected VK_EXT_mesh_shader + meshShader + taskShader + meshShaderQueries + bufferDeviceAddress + storageBuffer8BitAccess + depthClamp into Mojang's VulkanBackend required-set.");
    }

    private static void injectExtension(String name) {
        Set<String> current = VulkanBackend.REQUIRED_DEVICE_EXTENSIONS;
        if (current.contains(name)) return;
        try {
            current.add(name);
            return;
        } catch (UnsupportedOperationException immutable) {
            // Fall through to replacement.
        }
        Set<String> replacement = new HashSet<>(current);
        replacement.add(name);
        replaceStaticFinal("REQUIRED_DEVICE_EXTENSIONS", replacement);
    }

    private static void injectFeature(VulkanFeature feature) {
        Set<VulkanFeature> current = VulkanBackend.REQUIRED_DEVICE_FEATURES;
        if (current.contains(feature)) return;
        try {
            current.add(feature);
            return;
        } catch (UnsupportedOperationException immutable) {
            // Fall through to replacement.
        }
        Set<VulkanFeature> replacement = new HashSet<>(current);
        replacement.add(feature);
        replaceStaticFinal("REQUIRED_DEVICE_FEATURES", replacement);
    }

    /**
     * Stand up a throwaway VkInstance, enumerate physical devices, and return true if at least
     * one device exposes {@code VK_EXT_mesh_shader}. The instance is destroyed before we return
     * so Mojang's own instance creation isn't affected.
     *
     * <p>If any step fails (e.g. no Vulkan ICD, no devices), we conservatively return false —
     * safer to skip the injection than to break MC's backend init on machines without Vulkan.
     */
    private static boolean anyDeviceSupportsMeshShader() {
        long instanceHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("vulkium-probe"))
                .apiVersion(VK12.VK_API_VERSION_1_2);

            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .pApplicationInfo(appInfo);

            PointerBuffer pInstance = stack.callocPointer(1);
            int r = VK10.vkCreateInstance(ci, null, pInstance);
            if (r != VK10.VK_SUCCESS) return false;
            instanceHandle = pInstance.get(0);
        } catch (Throwable t) {
            return false;
        }

        VkInstance instance = new VkInstance(instanceHandle, VkInstanceCreateInfo.calloc().sType$Default());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.callocInt(1);
            int r = VK10.vkEnumeratePhysicalDevices(instance, pCount, null);
            if (r != VK10.VK_SUCCESS) return false;
            int count = pCount.get(0);
            if (count == 0) return false;

            PointerBuffer pDevices = stack.callocPointer(count);
            r = VK10.vkEnumeratePhysicalDevices(instance, pCount, pDevices);
            if (r != VK10.VK_SUCCESS) return false;

            for (int i = 0; i < count; i++) {
                VkPhysicalDevice phys = new VkPhysicalDevice(pDevices.get(i), instance);
                if (physicalDeviceHasExtension(phys, EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                VK10.vkDestroyInstance(instance, null);
            } catch (Throwable t) {
                LOGGER.debug("probe: vkDestroyInstance threw (ignored)", t);
            }
        }
    }

    private static boolean physicalDeviceHasExtension(VkPhysicalDevice phys, String name) {
        // Heap-alloc — device extension list can exceed LWJGL's 64 KB stack.
        IntBuffer pCount;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            pCount = stack.callocInt(1);
            int r = VK10.vkEnumerateDeviceExtensionProperties(phys, (String) null, pCount, null);
            if (r != VK10.VK_SUCCESS) return false;
            int count = pCount.get(0);
            if (count == 0) return false;
            VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count);
            try {
                r = VK10.vkEnumerateDeviceExtensionProperties(phys, (String) null, pCount, props);
                if (r != VK10.VK_SUCCESS) return false;
                for (int i = 0; i < count; i++) {
                    if (props.get(i).extensionNameString().equals(name)) return true;
                }
                return false;
            } finally {
                props.free();
            }
        }
    }

    private static void replaceStaticFinal(String fieldName, Object newValue) {
        try {
            Field f = VulkanBackend.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, newValue);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Vulkium failed to inject into VulkanBackend." + fieldName
                + " — probably a Mojang refactor; accesswidener entries need updating. Cause: " + e);
        }
    }
}
