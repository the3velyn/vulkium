package me.cortex.vulkium.blaze3d;

import org.joml.Matrix4f;

/**
 * Stores the bobbed modelview matrix captured from {@code GameRenderer.bobHurt/bobView} via
 * the {@code GameRendererBobMixin}. Consumed by {@code FrameDriver.onEndMain} when composing
 * the vulkium MVP so terrain matches vanilla camera bob + damage/nausea distortion.
 */
public final class BobViewTap {
    private static final Matrix4f MATRIX = new Matrix4f();
    private static volatile boolean valid = false;

    private BobViewTap() {}

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("vulkium/bob");
    private static final java.util.concurrent.atomic.AtomicLong CAPTURES = new java.util.concurrent.atomic.AtomicLong();

    public static void set(Matrix4f source) {
        synchronized (MATRIX) {
            MATRIX.set(source);
            valid = true;
        }
        long n = CAPTURES.incrementAndGet();
        // Periodic diagnostic: show a telltale element so we can tell identity vs active bob.
        // m30 (translation x) and m31 (translation y) are near-zero for identity, non-zero when
        // bobView is applying walk-cycle translation.
        if (n <= 4 || n % 300 == 0) {
            LOGGER.info("BobViewTap capture #{} m30={} m31={} m32={} identity={}",
                n, source.m30(), source.m31(), source.m32(), source.equals(new Matrix4f()));
        }
    }

    /** @return the captured matrix copied into {@code dst}, or {@code false} if nothing's been
     *  captured yet (render before first bob hook fired). */
    public static boolean read(Matrix4f dst) {
        if (!valid) return false;
        synchronized (MATRIX) {
            dst.set(MATRIX);
        }
        return true;
    }

    public static boolean hasValue() { return valid; }

    /** Call at the start of each frame so early-returning bobHurt/bobView (which skip our
     *  @At("TAIL") inject) don't leave a stale matrix from the previous frame in place. */
    public static void invalidate() {
        synchronized (MATRIX) {
            MATRIX.identity();
            valid = false;
        }
    }
}
