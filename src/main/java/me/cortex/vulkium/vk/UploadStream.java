package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame staging upload ring. Vulkan port of nvidium's
 * {@code UploadingBufferStream}.
 *
 * <p>A single persistently-mapped {@link StagingBuffer} of {@code sectionSize * sectionCount}
 * bytes is carved into {@code sectionCount} fixed-size sections, one per frame-in-flight. Within
 * a section a simple bump-allocator hands out 16-byte-aligned slices; callers write their data
 * directly into the mapped region. On {@link #commitFrame()} all pending {@code vkCmdCopyBuffer}
 * operations are recorded and submitted through Mojang's shared command encoder, then a trivial
 * signal-only queue submit bumps the section's per-frame timeline value — which the ring will
 * later wait on before re-using the same section.
 *
 * <h3>Section-advance policy</h3>
 * When {@link #upload} can't fit a request in the current section we advance the cursor to the
 * next section immediately (no fence wait yet). If that next section has outstanding GPU work
 * (its stored {@code lastSignalValue > 0}), we block on
 * {@link TimelineSemaphore#awaitUntil(long, long)} with a 5-second timeout, then reset its bump
 * cursor and retry. In the steady state with {@code sectionCount} &gt; in-flight-frames this wait
 * never fires: the wait is a safety net for bursty upload patterns that lap the ring inside a
 * single frame. A single request exceeding {@code sectionSize} is rejected — the caller is
 * expected to chunk its data itself.
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Allocate on the worker thread that owns mesh uploads or the render thread;
 * gate externally if you share instances.
 */
public final class UploadStream implements AutoCloseable {

    /** std430 storage-buffer alignment floor. Larger alignments are the caller's problem. */
    private static final long ALIGN = 16L;

    /** 5 seconds — generous enough for any real frame, short enough to surface a bug. */
    private static final long WAIT_TIMEOUT_NS = 5_000_000_000L;

    private final StagingBuffer staging;
    private final long sectionSize;
    private final int sectionCount;

    /** Signal value that will be (or has been) associated with each section's last commit. */
    private final long[] sectionSignalValue;

    private final TimelineSemaphore timeline;

    /** Current section index — the one {@link #upload} bumps into. */
    private int currentSection;

    /** Bump cursor within {@link #currentSection}, in bytes from the section base. */
    private long cursor;

    /** Pending copies queued during this frame. Flushed in {@link #commitFrame()}. */
    private final List<PendingCopy> pending = new ArrayList<>();

    /**
     * Per-section [minOffset, maxOffset) of host writes this frame. Used to issue a single
     * tight {@code vmaFlushAllocation} per touched section in {@link #commitFrame()}.
     */
    private final long[] flushLo;
    private final long[] flushHi;

    private boolean closed;

    private UploadStream(StagingBuffer staging, long sectionSize, int sectionCount,
                         TimelineSemaphore timeline) {
        this.staging = staging;
        this.sectionSize = sectionSize;
        this.sectionCount = sectionCount;
        this.timeline = timeline;
        this.sectionSignalValue = new long[sectionCount];
        this.flushLo = new long[sectionCount];
        this.flushHi = new long[sectionCount];
        resetSectionWriteRange(0);
    }

    public static UploadStream create(long sectionSize, int sectionCount) {
        if (sectionSize <= 0 || sectionCount <= 0) {
            throw new IllegalArgumentException("sectionSize and sectionCount must be positive");
        }
        long aligned = alignUp(sectionSize, ALIGN);
        if (aligned != sectionSize) {
            throw new IllegalArgumentException("sectionSize must be a multiple of " + ALIGN);
        }
        long total = Math.multiplyExact(sectionSize, (long) sectionCount);
        StagingBuffer sb = StagingBuffer.allocate(total);
        TimelineSemaphore ts = TimelineSemaphore.create(0L);
        return new UploadStream(sb, sectionSize, sectionCount, ts);
    }

    /** Reserve a slice in the current section for a future copy into {@code target}. */
    public long upload(DeviceBuffer target, long targetOffset, int byteCount) {
        return upload((VkBuffer) target, targetOffset, byteCount);
    }

    /**
     * Reserve a 16-byte-aligned slice of {@code byteCount} bytes in the current section and
     * queue a copy into {@code target[targetOffset..+byteCount]}. Returns a raw native pointer
     * that the caller must write into before {@link #commitFrame()}.
     */
    public long upload(VkBuffer target, long targetOffset, int byteCount) {
        if (closed) throw new IllegalStateException("UploadStream closed");
        if (byteCount <= 0) throw new IllegalArgumentException("byteCount must be positive");
        if ((long) byteCount > sectionSize) {
            throw new IllegalArgumentException(
                "Upload request " + byteCount + " exceeds sectionSize " + sectionSize);
        }
        if (targetOffset < 0 || targetOffset + byteCount > target.size()) {
            throw new IllegalArgumentException("target range out of bounds");
        }

        long aligned = alignUp(cursor, ALIGN);
        if (aligned + byteCount > sectionSize) {
            advanceSection();
            aligned = cursor; // advanceSection leaves cursor at 0, already aligned
        }
        cursor = aligned + byteCount;

        long sectionBase = (long) currentSection * sectionSize;
        long offsetInBuffer = sectionBase + aligned;

        // Track host-written range for a tight flush at commit time.
        if (flushLo[currentSection] == -1L) {
            flushLo[currentSection] = offsetInBuffer;
        }
        flushHi[currentSection] = offsetInBuffer + byteCount;

        pending.add(new PendingCopy(offsetInBuffer, target.handle(), targetOffset, byteCount));

        return staging.mappedPointer() + offsetInBuffer;
    }

    /**
     * Record all pending copies into a command buffer, submit it through Mojang's frame encoder,
     * signal this section's timeline value, then advance to the next section. Must be called
     * once per frame even if no uploads happened — cheap when {@code pending} is empty.
     */
    public void commitFrame() {
        if (closed) throw new IllegalStateException("UploadStream closed");

        if (pending.isEmpty()) {
            // Still advance: callers may rely on the ring stepping each frame so backpressure
            // gets spread across sections. Nothing to flush, nothing to signal though — a
            // no-op signal would force every consumer to track a per-section "has ever been
            // submitted" flag just to avoid a spurious wait.
            advanceSection();
            return;
        }

        // 1. Flush the host writes for every section we touched this commit.
        for (int i = 0; i < sectionCount; i++) {
            if (flushLo[i] != -1L) {
                staging.flush(flushLo[i], flushHi[i] - flushLo[i]);
            }
        }

        // 2. Record & submit the copies through Mojang's shared encoder. This preserves queue
        // ordering with their frame submission — the copy lands before any downstream draw
        // reads the device buffer, because Mojang ends the in-flight primary before executing
        // ours.
        final List<PendingCopy> snapshot = new ArrayList<>(pending);
        pending.clear();

        CommandRecorder.recordAndSubmit(cmd -> {
            // Group copies by destination buffer — one vkCmdCopyBuffer per target. The copy
            // array is allocated on the HEAP (not MemoryStack) because during heavy ingest a
            // single frame can accumulate 10k+ pending copies (drainPerFrame=4096 × 2-3 copies
            // per section + region meta slabs), and VkBufferCopy.SIZEOF × that count easily
            // blows past LWJGL's 64 KB stack. Heap alloc is negligible cost at this rate.
            int i = 0;
            while (i < snapshot.size()) {
                long dst = snapshot.get(i).dstBuffer;
                int j = i;
                while (j < snapshot.size() && snapshot.get(j).dstBuffer == dst) j++;
                int run = j - i;

                VkBufferCopy.Buffer regions = VkBufferCopy.calloc(run);
                try {
                    for (int k = 0; k < run; k++) {
                        PendingCopy p = snapshot.get(i + k);
                        regions.get(k).srcOffset(p.srcOffset).dstOffset(p.dstOffset).size(p.byteCount);
                    }
                    VK10.vkCmdCopyBuffer(cmd, staging.handle(), dst, regions);
                } finally {
                    regions.free();
                }
                i = j;
            }
        });

        // 3. Signal this frame's timeline value via an empty queue submit. Queue submits are
        // strictly ordered on the graphics queue, so this fences *after* the copy submit
        // just issued. Consumers that later block on awaitUntil(sig) see the copy as complete.
        long sig = timeline.nextSignalValue();
        submitSignalOnly(sig);

        // Stamp EVERY section that received host writes this frame with `sig`. Mid-frame
        // advances can touch multiple sections in a single commit (heavy ingest bursts like
        // F3+A) — stamping only `currentSection` would leave the earlier sections with
        // signalValue=0, so when the ring loops back `advanceSection` wouldn't wait, racing
        // the GPU still reading those sections' staging bytes. Staging corruption under
        // that race manifested as "reload breaks existing and new chunks". All writes this
        // frame go into the same submit, so they all retire at the same timeline value.
        for (int i = 0; i < sectionCount; i++) {
            if (flushLo[i] != -1L) {
                sectionSignalValue[i] = sig;
                resetSectionWriteRange(i);
            }
        }

        advanceSection();
    }

    /** Advance {@link #currentSection}; waits for and resets the next section if it's busy. */
    private void advanceSection() {
        int next = (currentSection + 1) % sectionCount;
        // If the next section has host writes from THIS frame that haven't been committed yet,
        // we must flush + submit the pending copies before reusing the staging bytes. Otherwise
        // the next writes clobber bytes that the still-queued vkCmdCopyBuffer is about to copy.
        // Without this, heavy ingest bursts (render-distance change → thousands of sections in
        // one frame) corrupt the uploaded arena data.
        if (flushLo[next] != -1L) {
            midFrameCommit();
        }
        long waitValue = sectionSignalValue[next];
        if (waitValue > 0L) {
            // The next slot is pinned to a previous submit that hasn't retired yet. Block.
            // With sectionCount >= Mojang's MAX_FRAMES_IN_FLIGHT this is a no-op wait — the
            // semaphore has already passed the value by the time we loop back.
            boolean signalled = timeline.awaitUntil(waitValue, WAIT_TIMEOUT_NS);
            if (!signalled) {
                throw new RuntimeException(
                    "UploadStream: timeline wait for section " + next + " @ value " + waitValue
                        + " timed out after " + WAIT_TIMEOUT_NS + "ns — GPU hang?");
            }
            sectionSignalValue[next] = 0L;
        }
        currentSection = next;
        cursor = 0L;
        resetSectionWriteRange(next);
    }

    /** Flush + submit currently-pending copies, signal, then stamp ALL touched sections with
     *  the signal value. Used when {@link #advanceSection()} needs to recycle a section that
     *  still has uncommitted host writes queued from earlier in the same frame. Does NOT call
     *  advanceSection itself — the caller continues the wrap-around wait after this returns. */
    private void midFrameCommit() {
        if (pending.isEmpty()) return; // nothing to flush; flushLo != -1 but no queued copy — safe to reuse

        // 1. Flush host writes for every section we've touched so far.
        for (int i = 0; i < sectionCount; i++) {
            if (flushLo[i] != -1L) {
                staging.flush(flushLo[i], flushHi[i] - flushLo[i]);
            }
        }

        // 2. Record + submit copies.
        final java.util.List<PendingCopy> snapshot = new java.util.ArrayList<>(pending);
        pending.clear();
        CommandRecorder.recordAndSubmit(cmd -> {
            int i = 0;
            while (i < snapshot.size()) {
                long dst = snapshot.get(i).dstBuffer;
                int j = i;
                while (j < snapshot.size() && snapshot.get(j).dstBuffer == dst) j++;
                int run = j - i;
                VkBufferCopy.Buffer regions = VkBufferCopy.calloc(run);
                try {
                    for (int k = 0; k < run; k++) {
                        PendingCopy p = snapshot.get(i + k);
                        regions.get(k).srcOffset(p.srcOffset).dstOffset(p.dstOffset).size(p.byteCount);
                    }
                    VK10.vkCmdCopyBuffer(cmd, staging.handle(), dst, regions);
                } finally {
                    regions.free();
                }
                i = j;
            }
        });

        // 3. Signal and stamp every touched section.
        long sig = timeline.nextSignalValue();
        submitSignalOnly(sig);
        for (int i = 0; i < sectionCount; i++) {
            if (flushLo[i] != -1L) {
                sectionSignalValue[i] = sig;
                resetSectionWriteRange(i);
            }
        }
    }

    private void resetSectionWriteRange(int idx) {
        flushLo[idx] = -1L;
        flushHi[idx] = -1L;
    }

    /** Empty VkSubmitInfo whose only purpose is to signal {@code value} on the timeline. */
    private void submitSignalOnly(long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                .sType$Default()
                .pSignalSemaphoreValues(stack.longs(value));

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pNext(timelineInfo.address())
                .pSignalSemaphores(stack.longs(timeline.handle()));

            int r = VK10.vkQueueSubmit(MojangVulkanBridge.vkGraphicsQueue(), submit, VK10.VK_NULL_HANDLE);
            if (r != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkQueueSubmit (UploadStream signal) failed: VkResult=" + r);
            }
        }
    }

    /** @return number of pending copies that have not yet been submitted. */
    public int pendingCount() { return pending.size(); }

    /** @return current section index (the one new allocations land in). */
    public int currentSection() { return currentSection; }

    /** @return the underlying staging buffer size in bytes. */
    public long totalSize() { return sectionSize * (long) sectionCount; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Any in-flight section submits reference the staging buffer. Wait out the GPU before
        // letting VMA drop it.
        VK10.vkDeviceWaitIdle(MojangVulkanBridge.vkDevice());
        pending.clear();
        timeline.close();
        staging.close();
    }

    private static long alignUp(long v, long a) {
        long d = v % a;
        return d == 0 ? v : v + (a - d);
    }

    private record PendingCopy(long srcOffset, long dstBuffer, long dstOffset, long byteCount) {}
}
