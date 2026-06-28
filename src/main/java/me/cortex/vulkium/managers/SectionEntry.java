package me.cortex.vulkium.managers;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * One captured chunk-section's worth of geometry, snapshotted at the moment Sodium's
 * {@code ChunkBuilderMeshingTask} finished.
 *
 * <p>Holds Sodium's already-packed 20-byte {@code CompactChunkVertex} bytes, keyed by
 * {@link TerrainRenderPass} (SOLID / CUTOUT / TRANSLUCENT singletons in
 * {@code DefaultTerrainRenderPasses}). The byte buffers are copies of Sodium's
 * {@code NativeBuffer} memory — Sodium recycles its buffer once the build task returns
 * to its caller, so we snapshot before queuing.
 *
 * <p>{@code MemoryUtil.memAlloc}-owned on the worker thread; the render thread frees
 * via {@code MemoryUtil.memFree} in {@code SectionManager.freeEntry} after upload.
 */
public final class SectionEntry {

    /** Per-layer geometry, keyed by Sodium's {@link TerrainRenderPass}. */
    public final Map<TerrainRenderPass, SodiumLayerGeometry> sodiumLayers = new HashMap<>();

    /** A single Sodium render pass's vertex bytes + per-facing segment counts. Vertex bytes
     *  are a memAlloc-owned copy of Sodium's {@code NativeBuffer}. {@link #vertexSegments}
     *  is a clone of Sodium's {@code BuiltSectionMeshParts.getVertexSegments()} — int[2*N]
     *  of {@code [count0, facing0, count1, facing1, ...]}, where facing values are
     *  {@code ModelQuadFacing.ordinal()} (POS_X=0, POS_Y=1, POS_Z=2, NEG_X=3, NEG_Y=4,
     *  NEG_Z=5, UNASSIGNED=6). Consumed by {@code TerrainUploader.uploadSectionSplit} to
     *  reorder vertices into vulkium's per-face arena bin layout. */
    public static final class SodiumLayerGeometry {
        public final ByteBuffer vertexBytes;
        public final int[] vertexSegments;
        public final int totalVertexCount;

        public SodiumLayerGeometry(ByteBuffer vertexBytes, int[] vertexSegments, int totalVertexCount) {
            this.vertexBytes = vertexBytes;
            this.vertexSegments = vertexSegments;
            this.totalVertexCount = totalVertexCount;
        }
    }
}
