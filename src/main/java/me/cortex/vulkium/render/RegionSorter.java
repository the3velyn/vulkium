package me.cortex.vulkium.render;

import me.cortex.vulkium.vk.ComputeDispatch;
import me.cortex.vulkium.vk.DeviceBuffer;
import me.cortex.vulkium.vk.UploadStream;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * First concrete compute pass in vulkium — runs the GPU region-section sort once per
 * visible region per frame. Dispatches {@code sorting/region_section_sorter.comp} with
 * one workgroup per region; each workgroup reads its regionId from
 * {@code sortingRegionList.data[gl_WorkGroupID.x]} and rewrites the 8-bit
 * local-section-id-post-sort field of every Section header in that region, so the CPU-side
 * mesh-shader draws can later emit section triangles in near-to-far order straight off the
 * header.
 *
 * <p>Ownership: the sorter owns the single {@link DeviceBuffer} holding the visible region
 * ID list ({@code MAX_REGIONS * 2} bytes of {@code uint16_t}). Callers stage writes into it
 * via {@link #uploadVisibleList(UploadStream, VisibilityTracker)} and then plumb the
 * returned device address into {@link SceneUniform#sortingRegionListPtr(long)} before
 * calling {@link #record(VkCommandBuffer, SceneUniform, int)}.
 *
 * <p>Compile timing: the ShaderModule is compiled eagerly inside the constructor via
 * {@link ComputeDispatch#create}. A shaderc failure therefore surfaces at construction
 * time (RuntimeException with a VkResult / GLSL diagnostic), not inside {@link #record}.
 */
public final class RegionSorter implements AutoCloseable {

    /** Max regions the sortingRegionList buffer can hold; matches RegionManager.MAX_REGIONS cap. */
    public static final int MAX_REGIONS = 1024;

    /** uint16_t per entry. */
    private static final int BYTES_PER_ENTRY = 2;

    private final ComputeDispatch dispatch;
    private final DeviceBuffer sortingRegionListBuffer;
    private boolean closed;

    public RegionSorter() {
        // The scene UBO at set=0, binding=0 is the only descriptor we need; everything else
        // is reached through buffer-reference pointers stored inside the UBO itself.
        this.dispatch = ComputeDispatch.create(
            "sorting/region_section_sorter.comp",
            null,
            0,
            List.of(new ComputeDispatch.Binding(0, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1)));

        // uint16_t data[MAX_REGIONS]. DeviceBuffer.allocate() enables SHADER_DEVICE_ADDRESS,
        // which is required since the shader reaches this through a buffer_reference pointer.
        this.sortingRegionListBuffer = DeviceBuffer.allocate((long) MAX_REGIONS * BYTES_PER_ENTRY);
    }

    /**
     * Upload the visible region IDs from {@link VisibilityTracker} into the GPU-side
     * sortingRegionList buffer through {@code stream}. Call once per frame before
     * {@link #record(VkCommandBuffer, SceneUniform, int)}.
     *
     * <p>The count is capped at {@link #MAX_REGIONS}; anything past that is silently
     * dropped because the underlying buffer can't hold more and the dispatch size is
     * driven from the same cap.
     *
     * <p>Failure modes from {@link UploadStream#upload}:
     * <ul>
     *   <li>If the caller-owned stream has been constructed with {@code sectionSize} smaller
     *       than {@code MAX_REGIONS * 2} bytes, upload throws
     *       {@code IllegalArgumentException} — surfaces as a config bug rather than being
     *       silently absorbed.</li>
     *   <li>If the ring's next section is still in-flight, {@link UploadStream} blocks on a
     *       timeline-semaphore wait (see its section-advance policy). It doesn't throw on
     *       back-pressure in the normal case; it only throws on a 5-second timeout, which
     *       would indicate a GPU hang — we propagate unchanged.</li>
     * </ul>
     *
     * @return the buffer's device address (to write into SceneUniform.sortingRegionListPtr)
     */
    public long uploadVisibleList(UploadStream stream, VisibilityTracker tracker) {
        if (closed) throw new IllegalStateException("RegionSorter is closed");

        int visible = Math.min(tracker.visibleRegionCount(), MAX_REGIONS);
        if (visible == 0) {
            // Nothing to upload; the shader won't be dispatched anyway. Returning the buffer
            // address is still useful: the scene UBO wants a non-null pointer even when the
            // bound range is empty (some validation layers flag zero-BDA reads).
            return sortingRegionListBuffer.deviceAddress();
        }

        int byteCount = visible * BYTES_PER_ENTRY;
        long ptr = stream.upload(sortingRegionListBuffer, 0L, byteCount);

        // Walk the tracker and spill uint16 region IDs into the staging slice in-order.
        // The forEachVisibleRegion callback hands us regionIds in near-to-far order, which
        // is exactly the order the shader wants for gl_WorkGroupID.x indexing.
        final long base = ptr;
        final int cap = visible;
        final int[] idx = { 0 };
        tracker.forEachVisibleRegion(id -> {
            if (idx[0] >= cap) return;
            MemoryUtil.memPutShort(base + ((long) idx[0] * BYTES_PER_ENTRY), (short) (id & 0xFFFF));
            idx[0]++;
        });

        return sortingRegionListBuffer.deviceAddress();
    }

    /**
     * Record a dispatch into {@code cmd}. Assumes the sortingRegionList buffer is already
     * populated via {@link #uploadVisibleList(UploadStream, VisibilityTracker)} and the
     * {@code SceneUniform}'s {@code sortingRegionListPtr} points at it.
     *
     * <p>No-op (no workgroups dispatched) when {@code visibleRegionCount == 0}; Vulkan
     * forbids {@code vkCmdDispatch(0, 1, 1)} and hammering validation with it is noise.
     */
    public void record(VkCommandBuffer cmd, SceneUniform sceneUniform, int visibleRegionCount) {
        if (closed) throw new IllegalStateException("RegionSorter is closed");
        if (visibleRegionCount <= 0) return;

        int groupsX = Math.min(visibleRegionCount, MAX_REGIONS);

        dispatch.record(
            cmd,
            null,
            bindings -> bindings.uniformBuffer(
                0,
                sceneUniform.buffer().handle(),
                0L,
                SceneUniform.SCENE_UBO_SIZE),
            groupsX, 1, 1);
    }

    public DeviceBuffer sortingRegionListBuffer() {
        return sortingRegionListBuffer;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // ComputeDispatch first (references shader module + pipeline objects that the driver
        // might still have pending refs to), then the device buffer.
        dispatch.close();
        sortingRegionListBuffer.close();
    }
}
