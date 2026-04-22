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
 *
 * <p><b>Ring buffer, not a single slot.</b> The CPU rewrites this UBO every frame (full
 * rewrite in {@code prepareFrame} and again mid-frame via {@code flushMvp} after bob +
 * distortion finalize). With {@code MAX_SUBMITS_IN_FLIGHT=2}, frame N+1's CPU writes could
 * land on the same bytes an in-flight frame-N submit is still reading via its push-descriptor
 * UBO binding — a WAR race with no synchronization. Same hazard class as
 * {@link OpaqueDispatchList} / {@link TranslucentSectionSorter}; same fix. Symptom on NVIDIA
 * Windows: ~10s of correct rendering, then a sudden GPU hang surfacing as MC's
 * VulkanCommandEncoder 5s semaphore timeout. Linux's driver serializes the hazard; Windows
 * walks off the end.
 *
 * <p>Three slots: current frame + up to {@code MAX_SUBMITS_IN_FLIGHT=2} prior frames still
 * reading. Slot rotation happens once per frame in {@link #rotate()}; all setters +
 * {@link #flush()}/{@link #flushMvp()} after that target the new slot. Consumers that hold
 * a {@code buffer()} or {@code deviceAddress()} handle must re-resolve it each frame — the
 * underlying VkBuffer changes when the slot rotates. All current consumers
 * ({@link PrimaryTerrainPass#record}, {@link RegionCuller}, {@link SectionCuller}) already
 * call {@code sceneUniform.buffer().handle()} at record time, so the rotation is transparent.
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

    /** Matches Mojang's {@code MAX_SUBMITS_IN_FLIGHT=2} plus one for the frame being written.
     *  Same constant as {@link OpaqueDispatchList#RING_SLOTS} / {@code TranslucentSectionSorter}
     *  — if Mojang ever bumps the in-flight cap, all three must move together. */
    private static final int RING_SLOTS = 3;

    private final StagingBuffer[] buffers = new StagingBuffer[RING_SLOTS];
    private final ByteBuffer[] views = new ByteBuffer[RING_SLOTS];
    /** Which ring slot CURRENT writes target. Callers access {@code buffer()} +
     *  {@code deviceAddress()} for THIS slot; the GPU reads it via the push-descriptor
     *  UBO binding recorded while this slot is current. */
    private int currentSlot = 0;
    /** Frame counter; incremented on every {@link #rotate()} call. Mod RING_SLOTS gives the
     *  next slot to write into. */
    private long frameCounter = 0L;

    public SceneUniform() {
        // Three host-visible, persistently-mapped UBO slots. Each 256B × 3 = 768B — negligible
        // memory cost vs. the hazard class it fixes.
        for (int s = 0; s < RING_SLOTS; s++) {
            buffers[s] = StagingBuffer.allocate(SCENE_UBO_SIZE);
            views[s] = buffers[s].mapped();
        }
    }

    /** Advance to the next ring slot. Must be called ONCE per frame, BEFORE any setters,
     *  flush, or {@code buffer()}/{@code deviceAddress()} access for this frame. Safe to
     *  call without subsequent writes — the slot's contents from 3 frames ago are still
     *  present and stale, so the caller is responsible for writing fresh data before the
     *  next draw binds this slot. In practice {@link Renderer#prepareFrame} does a full
     *  rewrite every frame, so the stale contents are always overwritten before use. */
    public void rotate() {
        currentSlot = (int) ((frameCounter++) % RING_SLOTS);
    }

    /** The current-slot's staging buffer. Consumers must call this at record time so the
     *  returned handle matches the slot the CPU is currently writing — caching the handle
     *  across frames defeats the ring. */
    public StagingBuffer buffer() { return buffers[currentSlot]; }
    public long deviceAddress() { return buffers[currentSlot].deviceAddress(); }

    public SceneUniform mvp(Matrix4f m) {
        m.get(OFFSET_MVP, views[currentSlot]);
        return this;
    }

    public SceneUniform chunkPosition(int x, int y, int z, int w) {
        ByteBuffer view = views[currentSlot];
        view.putInt(OFFSET_CHUNK_POSITION,       x);
        view.putInt(OFFSET_CHUNK_POSITION +  4,  y);
        view.putInt(OFFSET_CHUNK_POSITION +  8,  z);
        view.putInt(OFFSET_CHUNK_POSITION + 12,  w);
        return this;
    }

    public SceneUniform subchunkOffset(float x, float y, float z, float w) {
        ByteBuffer view = views[currentSlot];
        view.putFloat(OFFSET_SUBCHUNK_OFFSET,       x);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET +  4,  y);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET +  8,  z);
        view.putFloat(OFFSET_SUBCHUNK_OFFSET + 12,  w);
        return this;
    }

    public SceneUniform fogColour(float r, float g, float b, float a) {
        ByteBuffer view = views[currentSlot];
        view.putFloat(OFFSET_FOG_COLOUR,       r);
        view.putFloat(OFFSET_FOG_COLOUR +  4,  g);
        view.putFloat(OFFSET_FOG_COLOUR +  8,  b);
        view.putFloat(OFFSET_FOG_COLOUR + 12,  a);
        return this;
    }

    public SceneUniform regionIndicesPtr(long ptr)      { views[currentSlot].putLong(OFFSET_REGION_INDICES_PTR, ptr);      return this; }
    public SceneUniform regionDataPtr(long ptr)         { views[currentSlot].putLong(OFFSET_REGION_DATA_PTR, ptr);         return this; }
    public SceneUniform sectionDataPtr(long ptr)        { views[currentSlot].putLong(OFFSET_SECTION_DATA_PTR, ptr);        return this; }
    public SceneUniform regionVisibilityPtr(long ptr)   { views[currentSlot].putLong(OFFSET_REGION_VISIBILITY_PTR, ptr);   return this; }
    public SceneUniform sectionVisibilityPtr(long ptr)  { views[currentSlot].putLong(OFFSET_SECTION_VISIBILITY_PTR, ptr);  return this; }
    public SceneUniform terrainCmdPtr(long ptr)         { views[currentSlot].putLong(OFFSET_TERRAIN_CMD_PTR, ptr);         return this; }
    public SceneUniform translucencyCmdPtr(long ptr)    { views[currentSlot].putLong(OFFSET_TRANSLUCENCY_CMD_PTR, ptr);    return this; }
    public SceneUniform sortingRegionListPtr(long ptr)  { views[currentSlot].putLong(OFFSET_SORTING_REGION_LIST_PTR, ptr); return this; }
    public SceneUniform terrainDataPtr(long ptr)        { views[currentSlot].putLong(OFFSET_TERRAIN_DATA_PTR, ptr);        return this; }
    public SceneUniform transformationArrPtr(long ptr)  { views[currentSlot].putLong(OFFSET_TRANSFORMATION_ARR_PTR, ptr);  return this; }
    public SceneUniform originArrPtr(long ptr)          { views[currentSlot].putLong(OFFSET_ORIGIN_ARR_PTR, ptr);          return this; }
    public SceneUniform statisticsPtr(long ptr)         { views[currentSlot].putLong(OFFSET_STATISTICS_PTR, ptr);          return this; }
    public SceneUniform opaqueDispatchListPtr(long ptr) { views[currentSlot].putLong(OFFSET_OPAQUE_DISPATCH_LIST_PTR, ptr); return this; }

    public SceneUniform screenSize(float w, float h) {
        ByteBuffer view = views[currentSlot];
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
        ByteBuffer view = views[currentSlot];
        view.putFloat(OFFSET_FOG_ENV_START,    envStart);
        view.putFloat(OFFSET_FOG_ENV_END,      envEnd);
        view.putFloat(OFFSET_FOG_RENDER_START, rdStart);
        view.putFloat(OFFSET_FOG_RENDER_END,   rdEnd);
        return this;
    }

    public SceneUniform regionCount(int count) {
        views[currentSlot].putShort(OFFSET_REGION_COUNT, (short) count);
        return this;
    }

    public SceneUniform frameId(int id) {
        views[currentSlot].put(OFFSET_FRAME_ID, (byte) id);
        return this;
    }

    /** Flush host writes to the GPU's view for the CURRENT slot. Call once per frame after
     *  all setters. */
    public void flush() {
        buffers[currentSlot].flush(0, SCENE_UBO_SIZE);
    }

    /** Narrow flush covering just the MVP matrix range (bytes 0..64) on the CURRENT slot.
     *  Used for mid-frame MVP refreshes (per-draw bob/distortion composite) after the
     *  full flush has already covered the rest of the UBO. */
    public void flushMvp() {
        buffers[currentSlot].flush(OFFSET_MVP, 64);
    }

    @Override
    public void close() {
        for (int s = 0; s < RING_SLOTS; s++) {
            if (buffers[s] != null) {
                try { buffers[s].close(); } catch (Throwable ignored) { /* swallow */ }
                buffers[s] = null;
            }
        }
    }
}
