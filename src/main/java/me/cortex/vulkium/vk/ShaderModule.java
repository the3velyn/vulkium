package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * One {@code VkShaderModule} compiled at runtime from GLSL via lwjgl-shaderc. Mojang's own
 * {@code com.mojang.blaze3d.vulkan.glsl.GlslCompiler} only covers the vert+frag stages of their
 * pipeline abstraction; vulkium needs task+mesh shaders too so we call shaderc directly.
 *
 * <p>The compile path resolves {@code #include <vulkium:terrain/task_common.glsl>} and the like
 * via a resource-loader callback against this JAR's classpath.
 */
public final class ShaderModule implements AutoCloseable {
    /** Resource root for vulkium shaders. {@code #include <vulkium:x>} → {@code ASSET_ROOT + "x"}. */
    private static final String ASSET_ROOT = "/assets/vulkium/shaders/";

    private final long handle;
    private final ShaderStage stage;
    private final String entryPoint;
    private boolean closed;

    private ShaderModule(long handle, ShaderStage stage, String entryPoint) {
        this.handle = handle;
        this.stage = stage;
        this.entryPoint = entryPoint;
    }

    public long handle()          { return handle; }
    public ShaderStage stage()    { return stage; }
    public String entryPoint()    { return entryPoint; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyShaderModule(MojangVulkanBridge.vkDevice(), handle, null);
    }

    /**
     * Compile a shader from a classpath resource under {@code /assets/vulkium/shaders/}.
     *
     * @param resourceName  e.g. {@code "terrain/mesh.glsl"} — anything under the asset root.
     * @param stage         the pipeline stage.
     * @param defines       preprocessor {@code #define} pairs (may be empty).
     */
    public static ShaderModule compileFromResource(String resourceName, ShaderStage stage,
                                                   Map<String, String> defines) {
        String source = readResource(ASSET_ROOT + resourceName);
        return compile(resourceName, source, stage, defines);
    }

    /** Compile a shader from an in-memory GLSL source string. */
    public static ShaderModule compile(String sourceName, String source, ShaderStage stage,
                                       Map<String, String> defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) {
            throw new RuntimeException("shaderc_compiler_initialize failed");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == MemoryUtil.NULL) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new RuntimeException("shaderc_compile_options_initialize failed");
        }

        // Keyed by the ShadercIncludeResult address; values are [nameBuf, contentBuf, struct].
        // The callbacks and compile phase all use this map.
        Map<Long, AllocatedInclude> liveIncludes = new HashMap<>();

        ShadercIncludeResolve resolver = ShadercIncludeResolve.create(
            (userData, requestedSourcePtr, type, requestingSourcePtr, includeDepth) -> {
                String requested = MemoryUtil.memUTF8Safe(requestedSourcePtr);
                return resolveInclude(requested, liveIncludes);
            });
        ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create(
            (userData, includeResultPtr) -> releaseInclude(includeResultPtr, liveIncludes));

        try {
            Shaderc.shaderc_compile_options_set_include_callbacks(options,
                resolver, release, MemoryUtil.NULL);
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_source_language(options,
                Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                Shaderc.shaderc_optimization_level_performance);

            if (defines != null) {
                for (Map.Entry<String, String> e : defines.entrySet()) {
                    Shaderc.shaderc_compile_options_add_macro_definition(options,
                        e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }

            long result = Shaderc.shaderc_compile_into_spv(compiler, source, stage.shadercKind,
                sourceName, "main", options);
            if (result == MemoryUtil.NULL) {
                throw new RuntimeException("shaderc_compile_into_spv returned NULL for " + sourceName);
            }

            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String err = Shaderc.shaderc_result_get_error_message(result);
                    throw new RuntimeException("Shader compile failed (" + sourceName + "): " + err);
                }
                ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
                if (spv == null) throw new RuntimeException("shaderc produced empty SPIR-V");
                return createModule(spv, stage);
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            // Defensively free anything the release callback missed.
            for (AllocatedInclude a : liveIncludes.values()) a.free();
            liveIncludes.clear();
            release.free();
            resolver.free();
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static ShaderModule createModule(ByteBuffer spv, ShaderStage stage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(spv);
            LongBuffer pModule = stack.callocLong(1);
            int result = VK10.vkCreateShaderModule(MojangVulkanBridge.vkDevice(), info, null, pModule);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed: VkResult=" + result);
            }
            return new ShaderModule(pModule.get(0), stage, "main");
        }
    }

    private static long resolveInclude(String requestedSource, Map<Long, AllocatedInclude> live) {
        if (requestedSource == null) requestedSource = "";
        String normalized = normalizeInclude(requestedSource);
        String resourcePath = ASSET_ROOT + normalized;

        String content;
        String nameForError = normalized;
        try {
            content = readResource(resourcePath);
        } catch (RuntimeException missing) {
            // Represent the missing include as a zero-length result; shaderc surfaces a compile
            // error pointing at the requesting line, which is clearer than a callback throw.
            content = "";
            nameForError = "MISSING:" + normalized;
        }

        ByteBuffer nameBuf = MemoryUtil.memUTF8(nameForError, false);
        ByteBuffer contentBuf = MemoryUtil.memUTF8(content, false);

        ShadercIncludeResult struct = ShadercIncludeResult.calloc();
        long addr = struct.address();
        struct.source_name(nameBuf);
        ShadercIncludeResult.nsource_name_length(addr, nameBuf.remaining());
        struct.content(contentBuf);
        ShadercIncludeResult.ncontent_length(addr, contentBuf.remaining());
        struct.user_data(0L);

        live.put(addr, new AllocatedInclude(nameBuf, contentBuf, struct));
        return addr;
    }

    private static void releaseInclude(long includeResultPtr, Map<Long, AllocatedInclude> live) {
        AllocatedInclude a = live.remove(includeResultPtr);
        if (a != null) a.free();
    }

    /**
     * Maps {@code vulkium:terrain/mesh.glsl} or {@code <vulkium:terrain/mesh.glsl>} or even
     * {@code terrain/mesh.glsl} to a relative resource path under {@link #ASSET_ROOT}.
     */
    private static String normalizeInclude(String raw) {
        String s = raw.trim();
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s;
    }

    private static String readResource(String path) {
        try (InputStream in = ShaderModule.class.getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Resource not found: " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Reading " + path + ": " + e, e);
        }
    }

    private record AllocatedInclude(ByteBuffer name, ByteBuffer content, ShadercIncludeResult struct) {
        void free() {
            struct.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(content);
        }
    }
}
