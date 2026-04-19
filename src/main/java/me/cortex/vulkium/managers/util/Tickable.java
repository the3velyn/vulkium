package me.cortex.vulkium.managers.util;

/**
 * Per-frame hook. Registered into {@link TickableManager}; ticked once at end-of-frame on the
 * render thread. Replaces nvidium's per-type registration (which hard-coded
 * {@code UploadingBufferStream} / {@code DownloadTaskStream}).
 */
public interface Tickable {
    /** Called from the render thread once per frame, after the frame has been submitted. */
    void tick();
}
