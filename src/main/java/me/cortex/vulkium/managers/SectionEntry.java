package me.cortex.vulkium.managers;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

/**
 * One captured chunk-section's worth of geometry, snapshotted at the moment MC's
 * {@link net.minecraft.client.renderer.chunk.SectionCompiler} finished.
 *
 * <p>This type carries the raw vertex/index bytes the vulkium render pipeline will eventually
 * upload to its own VK buffers. Held only long enough for the main thread to ingest — worker
 * threads compile into this structure, main thread drains per frame and forwards into
 * {@code SectionManager}.
 *
 * <p>The raw {@link ByteBuffer}s here are <strong>copies</strong> of MC's MeshData buffers
 * (MC owns / recycles its own staging through {@code ByteBufferBuilder.Result}). We allocate
 * direct byte buffers via {@code MemoryUtil.memAlloc} on the worker thread, and the main
 * thread is responsible for freeing them after upload via {@code MemoryUtil.memFree}.
 */
public final class SectionEntry {

    /** Layer-keyed geometry data. Missing entries = that layer had no geometry. */
    public final Map<ChunkSectionLayer, LayerGeometry> layers = new EnumMap<>(ChunkSectionLayer.class);

    /** A single layer's vertex + index bytes + draw-state summary. */
    public static final class LayerGeometry {
        public final ByteBuffer vertexBytes;           // direct, memAlloc-owned
        public final ByteBuffer indexBytes;            // direct, memAlloc-owned, nullable (shared index buffer)
        public final int vertexCount;
        public final int indexCount;
        public final MeshData.DrawState drawState;

        public LayerGeometry(ByteBuffer vertexBytes, ByteBuffer indexBytes,
                             int vertexCount, int indexCount,
                             MeshData.DrawState drawState) {
            this.vertexBytes = vertexBytes;
            this.indexBytes = indexBytes;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.drawState = drawState;
        }
    }
}
