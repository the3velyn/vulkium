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

    public static void set(Matrix4f source) {
        synchronized (MATRIX) {
            MATRIX.set(source);
            valid = true;
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
}
