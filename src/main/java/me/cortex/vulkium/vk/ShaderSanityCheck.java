package me.cortex.vulkium.vk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Boot-time sanity compile of every shader vulkium ships. Runs once when {@code Vulkium} is
 * enabled — shaderc failures bubble up as log lines rather than first-draw crashes, so the
 * whole V6 matrix gets exercised well before any pipeline actually consumes it.
 *
 * <p>The compile does not create pipelines; modules are immediately destroyed after a successful
 * {@code vkCreateShaderModule}. Cost is one-off at startup (~100ms per shader on a warm JIT).
 *
 * <p>Failures are logged but don't disable vulkium — if a shader has a bug we want to see the
 * error, not silently fall back to vanilla.
 */
public final class ShaderSanityCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/shader-check");

    /** Each entry: shader resource path + stage + optional defines to trigger variants. */
    private record Entry(String path, ShaderStage stage, Map<String, String> defines) {}

    private static final List<Entry> SHADERS = List.of(
        new Entry("terrain/task.glsl", ShaderStage.TASK, Map.of()),
        new Entry("terrain/task.glsl", ShaderStage.TASK, Map.of("TRANSLUCENT_PASS", "1")),
        new Entry("terrain/mesh.glsl", ShaderStage.MESH, Map.of()),
        new Entry("terrain/mesh.glsl", ShaderStage.MESH, Map.of("TRANSLUCENT_PASS", "1")),
        new Entry("terrain/mesh.glsl", ShaderStage.MESH, Map.of("RENDER_FOG", "1")),
        new Entry("terrain/frag.frag", ShaderStage.FRAGMENT, Map.of()),
        new Entry("terrain/frag.frag", ShaderStage.FRAGMENT, Map.of("RENDER_FOG", "1")),
        new Entry("terrain/temporal_task.glsl", ShaderStage.TASK, Map.of()),
        new Entry("terrain/translucent/task.glsl", ShaderStage.TASK, Map.of()),
        new Entry("terrain/translucent/mesh.glsl", ShaderStage.MESH, Map.of()),
        new Entry("terrain/frag.frag", ShaderStage.FRAGMENT, Map.of("TRANSLUCENT_PASS", "1")),
        new Entry("occlusion/hzb_downsample.comp", ShaderStage.COMPUTE, Map.of()),
        new Entry("occlusion/region_cull.comp", ShaderStage.COMPUTE, Map.of()),
        new Entry("occlusion/section_cull.comp", ShaderStage.COMPUTE, Map.of())
    );

    private ShaderSanityCheck() {}

    public static Result runAll() {
        long t0 = System.nanoTime();
        int ok = 0;
        int fail = 0;
        StringBuilder errors = new StringBuilder();
        for (Entry e : SHADERS) {
            String label = e.path + " [" + e.stage + "]"
                + (e.defines.isEmpty() ? "" : " " + e.defines);
            try {
                try (ShaderModule m = ShaderModule.compileFromResource(e.path, e.stage, e.defines)) {
                    ok++;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("OK {}", label);
                    }
                }
            } catch (Throwable t) {
                fail++;
                errors.append("\n  FAIL ").append(label).append(" → ").append(t.getMessage());
            }
        }
        long durMs = (System.nanoTime() - t0) / 1_000_000L;
        if (fail == 0) {
            LOGGER.info("Shader sanity-check: {}/{} OK in {} ms", ok, SHADERS.size(), durMs);
        } else {
            LOGGER.warn("Shader sanity-check: {}/{} OK, {} FAIL in {} ms:{}",
                ok, SHADERS.size(), fail, durMs, errors.toString());
        }
        return new Result(ok, fail, durMs);
    }

    public record Result(int ok, int fail, long durationMs) {}
}
