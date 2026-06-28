package me.cortex.vulkium.managers;

import com.mojang.blaze3d.vertex.MeshData;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * One captured chunk-section's worth of geometry, snapshotted at the moment Sodium's
 * {@code ChunkBuilderMeshingTask} finished (sodium-edition; on the mc-26.2 standalone branch
 * the source is MC's own {@code SectionCompiler}).
 *
 * <p>This type carries the raw vertex bytes the vulkium render pipeline will upload to its
 * own VK buffers. Held only long enough for the main thread to ingest — worker threads
 * compile into this structure, main thread drains per frame and forwards into
 * {@code SectionManager}.
 *
 * <p>On the sodium branch the {@link #sodiumLayers} map carries Sodium's already-packed
 * 20-byte COMPACT vertex bytes (see {@link SodiumLayerGeometry}). The legacy
 * {@link #layers} field still exists for the standalone-era ingest path but is unused under
 * Sodium and slated for removal in the Stage 6 cleanup pass; do not populate both maps for
 * the same entry.
 *
 * <p>The raw {@link ByteBuffer}s here are <strong>copies</strong> of Sodium's NativeBuffer
 * memory — Sodium recycles its buffer once the build task returns to its caller, so we must
 * snapshot before queueing. We allocate direct buffers via {@code MemoryUtil.memAlloc} on
 * the worker thread; the main thread frees them after upload via {@code MemoryUtil.memFree}.
 */
public final class SectionEntry {

    /** Standalone-era layer-keyed geometry. Unused on the sodium branch — kept for source
     *  compatibility with code paths not yet rewritten; will be deleted in Stage 6 cleanup. */
    public final Map<ChunkSectionLayer, LayerGeometry> layers = new EnumMap<>(ChunkSectionLayer.class);

    /** Sodium-edition geometry, keyed by Sodium's {@link TerrainRenderPass} (SOLID / CUTOUT /
     *  TRANSLUCENT singletons in {@code DefaultTerrainRenderPasses}). Empty if this entry was
     *  produced by the legacy MC ingest path. */
    public final Map<TerrainRenderPass, SodiumLayerGeometry> sodiumLayers = new HashMap<>();

    /** A single layer's vertex + index bytes + draw-state summary (standalone-edition). */
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

    /** Sodium-edition per-layer geometry. Vertex bytes are a memAlloc-owned copy of
     *  Sodium's {@code NativeBuffer} (Sodium recycles its buffer after the build task
     *  returns). {@link #vertexSegments} is a clone of Sodium's
     *  {@code BuiltSectionMeshParts.getVertexSegments()} — int[2*N] of
     *  {@code [count0, facing0, count1, facing1, ...]}, where facing values are
     *  {@code ModelQuadFacing.ordinal()} (POS_X=0, POS_Y=1, POS_Z=2, NEG_X=3, NEG_Y=4,
     *  NEG_Z=5, UNASSIGNED=6). Used by Stage 2.5's face-binned dispatch to skip back-facing
     *  faces; not yet consumed in Stage 1/2. */
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
