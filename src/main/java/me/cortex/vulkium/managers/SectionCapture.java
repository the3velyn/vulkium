package me.cortex.vulkium.managers;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point into vulkium's chunk ingestion pipeline. MC 26.2's
 * {@link net.minecraft.client.renderer.chunk.CompiledSectionMesh} is mixed into so that, at
 * every successful section compile, the raw {@link MeshData} for each
 * {@link ChunkSectionLayer} is handed to us before MC's own uber-buffer upload happens.
 *
 * <p>This is the moment vulkium converts vanilla's per-section geometry into its own
 * region/section representation. For now we only count + log; buffer allocation + upload lands
 * in V5 (VK buffer abstractions) and region indexing lands in V4.
 *
 * <p>Called from the worker thread that owns the section compile — <strong>not</strong> the
 * render thread. Anything that eventually touches VK resources must defer to the render thread
 * or use a thread-safe producer/consumer hand-off.
 *
 * <p><strong>Section-key plumbing:</strong> the {@link CompileTaskMixin} wraps
 * {@code CompileTask.doTask} with {@link #beginSectionCompile(long)} / {@link #endSectionCompile()}
 * around the compile. The {@link me.cortex.vulkium.mixin.chunk.CompiledSectionMeshMixin} ctor
 * mixin reads the thread-local to tag the mesh with a real packed {@code SectionPos.asLong}.
 */
public final class SectionCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/capture");

    /** Monotonic counter of section-mesh captures (diagnostic). */
    private static final AtomicLong SECTIONS_CAPTURED = new AtomicLong();

    /** Running total vertex bytes across all layers (diagnostic). */
    private static final AtomicLong VERTEX_BYTES_TOTAL = new AtomicLong();

    /** Running total index bytes across all layers (diagnostic). */
    private static final AtomicLong INDEX_BYTES_TOTAL = new AtomicLong();

    /**
     * Worker-thread-local packed {@code SectionPos.asLong} set by {@code CompileTaskMixin}
     * around MC's compile call. {@code Long.MIN_VALUE} means "no compile in flight" (in which
     * case callers fall back to the legacy 0 sentinel).
     */
    private static final ThreadLocal<Long> COMPILING_SECTION = ThreadLocal.withInitial(() -> Long.MIN_VALUE);

    private SectionCapture() {}

    /** Called from {@code CompileTaskMixin} at doTask HEAD. */
    public static void beginSectionCompile(long sectionPosKey) {
        COMPILING_SECTION.set(sectionPosKey);
    }

    /** Called from {@code CompileTaskMixin} at doTask RETURN. */
    public static void endSectionCompile() {
        COMPILING_SECTION.set(Long.MIN_VALUE);
    }

    /** Read by the {@code CompiledSectionMesh} ctor mixin when it fires on the compile thread. */
    public static long currentCompilingSectionKey() {
        long v = COMPILING_SECTION.get();
        return v == Long.MIN_VALUE ? 0L : v;
    }

    /**
     * Called from {@code CompiledSectionMeshMixin} at section-mesh construction.
     *
     * @param sectionPosKey packed {@code SectionPos.asLong}, or 0 if unknown at this site.
     * @param results the result object MC's {@link SectionCompiler} just produced.
     */
    public static void onSectionMeshCompiled(long sectionPosKey, SectionCompiler.Results results) {
        Map<ChunkSectionLayer, MeshData> layers = results.renderedLayers;
        if (layers.isEmpty()) {
            return;
        }
        // Hand off to the SectionManager. It copies the raw bytes so MC can recycle its own
        // ByteBufferBuilder.Result memory safely.
        SectionManager.get().offerFromCompile(sectionPosKey, results);
        long vbTotal = 0L;
        long ibTotal = 0L;
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : layers.entrySet()) {
            MeshData m = entry.getValue();
            if (m == null) continue;
            var vb = m.vertexBuffer();
            var ib = m.indexBuffer();
            if (vb != null) vbTotal += vb.remaining();
            if (ib != null) ibTotal += ib.remaining();
        }
        long seq = SECTIONS_CAPTURED.incrementAndGet();
        VERTEX_BYTES_TOTAL.addAndGet(vbTotal);
        INDEX_BYTES_TOTAL.addAndGet(ibTotal);

        // Only log the first few — this fires from worker threads and can be very high-volume.
        if (seq <= 8 || seq % 1024 == 0) {
            LOGGER.info("Section capture #{} key=0x{} layers={} vbBytes={} ibBytes={} (totals vb={} ib={})",
                seq, Long.toHexString(sectionPosKey), layers.size(), vbTotal, ibTotal,
                VERTEX_BYTES_TOTAL.get(), INDEX_BYTES_TOTAL.get());
        }
    }

    // --- diagnostics ---------------------------------------------------------
    public static long sectionsCaptured() { return SECTIONS_CAPTURED.get(); }
    public static long vertexBytesTotal() { return VERTEX_BYTES_TOTAL.get(); }
    public static long indexBytesTotal()  { return INDEX_BYTES_TOTAL.get(); }
}
