package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
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

        injectExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);

        VulkanPNextStruct meshStruct = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
            VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF);
        injectFeature(new VulkanFeature(meshStruct, "meshShader",
            VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER));
        injectFeature(new VulkanFeature(meshStruct, "meshShaderQueries",
            VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADERQUERIES));

        // bufferDeviceAddress — Mojang doesn't enable this by default in MC 26.2, but vulkium
        // needs it for buffer-reference shader access. Feature struct is VK 1.2 core.
        VulkanPNextStruct bdaStruct = new VulkanPNextStruct(
            VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES,
            VkPhysicalDeviceBufferDeviceAddressFeatures.SIZEOF);
        injectFeature(new VulkanFeature(bdaStruct, "bufferDeviceAddress",
            VkPhysicalDeviceBufferDeviceAddressFeatures.BUFFERDEVICEADDRESS));

        LOGGER.info("Injected VK_EXT_mesh_shader + meshShader + meshShaderQueries + bufferDeviceAddress into Mojang's VulkanBackend required-set.");
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
