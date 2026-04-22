package me.cortex.vulkium.render;

import me.cortex.vulkium.vk.StagingBuffer;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * Host-visible, persistently-mapped Scene UBO. Mirrors the {@code std140 SceneData} block
 * declared in {@code /assets/vulkium/shaders/occlusion/scene.glsl}. Each frame the render
 * thread calls the typed setters, then hands the buffer's device address (or a push-descriptor
 * binding) to dispatched pipelines.
 *
 * <p>The scene block is a single layout (no RENDER_FOG variant). Fog params are always
 * present; disabling fog just clamps the start/end ranges so the linear curve stays at 0.
 *
 * <p>Offsets computed from std140 rules: mat4 is 64 bytes 16-aligned; vec4/ivec4 is 16 bytes
 * 16-aligned; 64-bit buffer-reference pointers are 8 bytes 8-aligned. Trailing scalars
 * are packed in declared order with std140 padding.
 */
public final class SceneUniform implements AutoCloseable {

    // --- std140 offsets ---------------------------------------------------------------
    public static final int OFFSET_MVP                     = 0;
    public static final int OFFSET_CHUNK_POSITION          = 64;
    public static final int OFFSET_SUBCHUNK_OFFSET         = 80;
    public static final int OFFSET_FOG_COLOUR              = 96;
    // Buffer-reference pointers — 8 bytes each, aligned to 8. std140 treats uint64 as 8-aligned.
    public static final int OFFSET_REGION_INDICES_PTR      = 112;
    public static final int OFFSET_REGION_DATA_PTR         = 120;
    public static final int OFFSET_SECTION_DATA_PTR        = 128;
    public static final int OFFSET_REGION_VISIBILITY_PTR   = 136;
    public static final int OFFSET_SECTION_VISIBILITY_PTR  = 144;
    public static final int OFFSET_TERRAIN_CMD_PTR         = 152;
    public static final int OFFSET_TRANSLUCENCY_CMD_PTR    = 160;
    public static final int OFFSET_SORTING_REGION_LIST_PTR = 168;
    public static final int OFFSET_TERRAIN_DATA_PTR        = 176;
    public static final int OFFSET_TRANSFORMATION_ARR_PTR  = 184;
    public static final int OFFSET_ORIGIN_ARR_PTR          = 192;
    public static final int OFFSET_STATISTICS_PTR          = 200;
    public static final int OFFSET_SCREEN_SIZE             = 208;
    // Vanilla fog model — four linear-curve floats. See terrain/fog.glsl.
    public static final int OFFSET_FOG_ENV_START           = 216;
    public static final int OFFSET_FOG_ENV_END             = 220;
    public static final int OFFSET_FOG_RENDER_START        = 224;
    public static final int OFFSET_FOG_RENDER_END          = 228;
    public static final int OFFSET_REGION_COUNT            = 232;   // uint16
    public static final int OFFSET_FRAME_ID                = 234;   // uint8
    // 8-byte BDA pointer at the next 8-aligned offset after frameId@234 + 1 byte + 5-byte
    // pad → 240. Previously at 232 when the fog block was 3 floats instead of 4.
    public static final int OFFSET_OPAQUE_DISPATCH_LIST_PTR = 240;
    /** Round up to 16-byte multiple for UBO binding. Ptr at 240 + 8 bytes = 248 → pad to 256. */
    public static final int SCENE_UBO_SIZE                 = 256;

    private final StagingBuffer buffer;
    private final ByteBuffer view;

    public SceneUniform() {
        // StagingBuffer is host-visible + persistently mapped. Using it for the scene UBO is
        // right because the CPU rewrites most fields every frame.
        this.buffer = StagingBuffer.allocate(SCENE_UBO_SIZE);
        this.view = buffer.mapped();
    }

    public StagingBuffer buffer() { return buffer; }
    public long deviceAddress() { return buffer.deviceAddress(); }

    public SceneUniform mvp(Matrix4f m) {
        m.get(OFFSET_MVP, view);
        return this;
    }

    public SceneUniform chunkPosition(int x, int y, int z, int w) {
        view.putInt(OFFSET_CHUNK_POSITION,       x);
        view.putInt(OFFSET_CHUNK_POSITION +  4,  y);
        view.putInt(OFFSET_CHUNK_POSITION +  8,  z);
        view.putInt(OFFSET_CHUNK_POSITION + 12,  w);
        return this;
    }

    public SceneUniform subchunkOffset(float x, float y, float z, float w) {
        view.putFloat(OFFSET_SUBCHUNK_OFFSET,       x);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET +  4,  y);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET +  8,  z);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET + 12,  w);
        return this;
    }

    public SceneUniform fogColour(float r, float g, float b, float a) {
        view.putFloat(OFFSET_FOG_COLOUR,       r);
        view.putFloat(OFFSET_FOG_COLOUR +  4,  g);
        view.putFloat(OFFSET_FOG_COLOUR +  8,  b);
        view.putFloat(OFFSET_FOG_COLOUR + 12,  a);
        return this;
    }

    public SceneUniform regionIndicesPtr(long ptr)      { view.putLong(OFFSET_REGION_INDICES_PTR, ptr);      return this; }
    public SceneUniform regionDataPtr(long ptr)         { view.putLong(OFFSET_REGION_DATA_PTR, ptr);         return this; }
    public SceneUniform sectionDataPtr(long ptr)        { view.putLong(OFFSET_SECTION_DATA_PTR, ptr);        return this; }
    public SceneUniform regionVisibilityPtr(long ptr)   { view.putLong(OFFSET_REGION_VISIBILITY_PTR, ptr);   return this; }
    public SceneUniform sectionVisibilityPtr(long ptr)  { view.putLong(OFFSET_SECTION_VISIBILITY_PTR, ptr);  return this; }
    public SceneUniform terrainCmdPtr(long ptr)         { view.putLong(OFFSET_TERRAIN_CMD_PTR, ptr);         return this; }
    public SceneUniform translucencyCmdPtr(long ptr)    { view.putLong(OFFSET_TRANSLUCENCY_CMD_PTR, ptr);    return this; }
    public SceneUniform sortingRegionListPtr(long ptr)  { view.putLong(OFFSET_SORTING_REGION_LIST_PTR, ptr); return this; }
    public SceneUniform terrainDataPtr(long ptr)        { view.putLong(OFFSET_TERRAIN_DATA_PTR, ptr);        return this; }
    public SceneUniform transformationArrPtr(long ptr)  { view.putLong(OFFSET_TRANSFORMATION_ARR_PTR, ptr);  return this; }
    public SceneUniform originArrPtr(long ptr)          { view.putLong(OFFSET_ORIGIN_ARR_PTR, ptr);          return this; }
    public SceneUniform statisticsPtr(long ptr)         { view.putLong(OFFSET_STATISTICS_PTR, ptr);          return this; }
    public SceneUniform opaqueDispatchListPtr(long ptr) { view.putLong(OFFSET_OPAQUE_DISPATCH_LIST_PTR, ptr); return this; }

    public SceneUniform screenSize(float w, float h) {
        view.putFloat(OFFSET_SCREEN_SIZE,     w);
        view.putFloat(OFFSET_SCREEN_SIZE + 4, h);
        return this;
    }

    /** Vanilla fog params. MC distinguishes environmental fog (spherical distance, for
     *  underwater / lava / Nether) from render-distance fog (cylindrical, for RD fade).
     *  Final lerp = max of both linear curves, each clamped to [0, 1]. See
     *  {@code terrain/fog.glsl#computeFogLerp}. Source is {@code cam.fogData} from MC's
     *  {@link net.minecraft.client.renderer.fog.FogRenderer}. */
    public SceneUniform fog(float envStart, float envEnd, float rdStart, float rdEnd) {
        view.putFloat(OFFSET_FOG_ENV_START,    envStart);
        view.putFloat(OFFSET_FOG_ENV_END,      envEnd);
        view.putFloat(OFFSET_FOG_RENDER_START, rdStart);
        view.putFloat(OFFSET_FOG_RENDER_END,   rdEnd);
        return this;
    }

    public SceneUniform regionCount(int count) {
        view.putShort(OFFSET_REGION_COUNT, (short) count);
        return this;
    }

    public SceneUniform frameId(int id) {
        view.put(OFFSET_FRAME_ID, (byte) id);
        return this;
    }

    /** Flush host writes to the GPU's view. Call once per frame after all setters. */
    public void flush() {
        buffer.flush(0, SCENE_UBO_SIZE);
    }

    /** Narrow flush covering just the MVP matrix range (bytes 0..64). Use for mid-frame
     *  refreshes that only update MVP (per-draw bob/distortion composite) — avoids re-
     *  flushing the entire 240-byte UBO when everything else is already current. */
    public void flushMvp() {
        buffer.flush(OFFSET_MVP, 64);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
