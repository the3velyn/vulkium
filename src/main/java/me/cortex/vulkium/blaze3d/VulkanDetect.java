package me.cortex.vulkium.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorIndexingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Probes whether Mojang's Vulkan backend is active AND the GPU supports the set of features
 * vulkium strictly requires:
 *
 * <ul>
 *   <li>{@code VK_EXT_mesh_shader} — mesh/task shaders. The whole point of vulkium.</li>
 *   <li>{@code bufferDeviceAddress} — for bindless pointer-style UBO access (replaces
 *       nvidium's {@code NV_shader_buffer_load}).</li>
 *   <li>{@code descriptorIndexing} — bindless-ish texture access.</li>
 *   <li>{@code sparseBinding} + {@code sparseResidencyBuffer} — for the terrain arena.
 *       Falls back to dense allocation if unsupported.</li>
 *   <li>{@code VK_KHR_push_descriptor} — MC 26.2 baseline.</li>
 * </ul>
 *
 * <p>Turing+ / RDNA3+ / Arc+ GPUs support all of these. Vulkium self-disables on anything older.
 */
public final class VulkanDetect {

    public record ProbeResult(
        boolean vulkanBackendActive,
        boolean meshShader,
        boolean meshShaderQueries,
        boolean bufferDeviceAddress,
        boolean descriptorIndexing,
        boolean sparseBinding,
        boolean sparseResidencyBuffer,
        boolean pushDescriptor,
        boolean synchronization2,
        String deviceName,
        String reason
    ) {
        /** Strict gate: everything vulkium needs, no graceful fallback. */
        public boolean meetsVulkiumGate() {
            return vulkanBackendActive
                && meshShader
                && bufferDeviceAddress
                && descriptorIndexing;
            // sparseBinding is advisory — we can run a dense fallback arena.
            // pushDescriptor + synchronization2 are effectively guaranteed by MC 26.2's VK 1.2
            // baseline; report them for diagnostics but don't gate.
        }

        @Override
        public String toString() {
            return String.format(
                "device=%s backend=vulkan mesh=%s mesh_queries=%s bda=%s desc_indexing=%s sparse=%s sparse_res_buf=%s push_desc=%s sync2=%s",
                deviceName, meshShader, meshShaderQueries, bufferDeviceAddress,
                descriptorIndexing, sparseBinding, sparseResidencyBuffer, pushDescriptor, synchronization2);
        }
    }

    private VulkanDetect() {}

    public static ProbeResult probe() {
        VulkanDevice vk = MojangVulkanBridge.vulkanDevice();
        if (vk == null) {
            return new ProbeResult(false, false, false, false, false, false, false, false, false,
                "(OpenGL backend active)",
                "Mojang's OpenGL backend is active. Enable 'Prefer Vulkan' in MC Video Settings.");
        }

        // We get the VkPhysicalDevice via our VulkanDeviceMixin which captures it at ctor time.
        var phys = MojangVulkanBridge.vkPhysicalDevice();
        Set<String> enabledExts = MojangVulkanBridge.enabledDeviceExtensions();
        String deviceName = vk.getDeviceInfo() != null ? vk.getDeviceInfo().toString() : "(unknown)";

        if (phys == null) {
            // Mixin hasn't fired — probed too early. Shouldn't happen in normal client flow.
            return new ProbeResult(true, false, false, false, false, false, false, false, false,
                deviceName,
                "Vulkium probe ran before VulkanDeviceMixin captured the physical device. This is a timing bug.");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Chain the feature structs we care about
            var meshFeat = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack).sType$Default();
            var diFeat   = VkPhysicalDeviceDescriptorIndexingFeatures.calloc(stack).sType$Default();
            var bdaFeat  = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();

            meshFeat.pNext(diFeat.address());
            diFeat.pNext(bdaFeat.address());

            var feat2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                .pNext(meshFeat.address());

            VK11.vkGetPhysicalDeviceFeatures2(phys, feat2);
            VkPhysicalDeviceFeatures coreFeat = feat2.features();

            // Extension-gated features: extension must be enabled AND the feature bit must be on.
            // MC 26.2 runs Vulkan 1.2; many of these extensions are core-promoted there.
            //
            // **Subtlety:** `enabledExts` holds what Mojang enabled at device creation. Mojang
            // has no reason to enable VK_EXT_mesh_shader on its own, so we check:
            //   (a) the physical device supports the extension (queryable via
            //       vkEnumerateDeviceExtensionProperties — we scan it below)
            //   (b) the feature bit is on in the pNext chain
            //   (c) Mojang enabled it (via our VulkanBackendMixin)
            // If (a)+(b) but not (c), vulkium injects the extension via the mixin. At probe
            // time (post-device-creation) all three must hold for us to actually use mesh shaders.
            boolean meshExtPhysicallySupported = physicalDeviceHasExtension(phys,
                EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            boolean meshExtEnabled = enabledExts.contains(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            boolean meshShader = meshExtEnabled && meshFeat.meshShader();
            boolean meshShaderQueries = meshFeat.meshShaderQueries();
            boolean bda = bdaFeat.bufferDeviceAddress();
            // VkPhysicalDeviceDescriptorIndexingFeatures doesn't carry a top-level rollup bit
            // (that's on VkPhysicalDeviceVulkan12Features). We just check the sub-features we
            // actually need directly.
            boolean di = diFeat.runtimeDescriptorArray()
                    && diFeat.shaderSampledImageArrayNonUniformIndexing()
                    && diFeat.descriptorBindingVariableDescriptorCount();
            boolean pushDesc = enabledExts.contains(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
            boolean sync2 = enabledExts.contains(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
            boolean sparse = coreFeat.sparseBinding();
            boolean sparseResBuf = coreFeat.sparseResidencyBuffer();

            if (!meshShader) {
                String reason;
                if (!meshExtPhysicallySupported) {
                    reason = "GPU / driver does not expose VK_EXT_mesh_shader. Vulkium requires a Turing+ NVIDIA, RDNA3+ AMD, or Arc+ Intel GPU.";
                } else if (!meshExtEnabled) {
                    reason = "VK_EXT_mesh_shader is supported by this GPU but Mojang's VkDevice was created without it enabled. VulkanBackendMixin should inject the extension — this state means the mixin didn't fire.";
                } else {
                    reason = "VK_EXT_mesh_shader is enabled but meshShader feature bit is false. This shouldn't happen on supported hardware.";
                }
                return new ProbeResult(true, false, meshShaderQueries, bda, di, sparse, sparseResBuf,
                    pushDesc, sync2, deviceName, reason);
            }
            if (!bda) {
                return new ProbeResult(true, true, meshShaderQueries, false, di, sparse, sparseResBuf,
                    pushDesc, sync2, deviceName,
                    "bufferDeviceAddress feature not available. Vulkium requires it for bindless pointer-style UBO access.");
            }
            if (!di) {
                return new ProbeResult(true, true, meshShaderQueries, bda, false, sparse, sparseResBuf,
                    pushDesc, sync2, deviceName,
                    "Descriptor indexing / runtimeDescriptorArray not available. Vulkium requires it for bindless texture access.");
            }

            return new ProbeResult(true, true, meshShaderQueries, bda, di, sparse, sparseResBuf,
                pushDesc, sync2, deviceName, "OK");
        }
    }

    /**
     * Check whether the physical device advertises an extension, independent of whether Mojang
     * enabled it at {@code vkCreateDevice} time. Used by the probe to distinguish
     * "GPU doesn't support it" from "Mojang didn't enable it."
     */
    private static boolean physicalDeviceHasExtension(org.lwjgl.vulkan.VkPhysicalDevice phys, String name) {
        // Heap-alloc the extension-properties buffer — a device typically has 200-400+
        // extensions × 260 bytes each, which overflows LWJGL's 64 KB default stack.
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
}
