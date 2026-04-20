package me.cortex.vulkium.blaze3d;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Stores the fully-composed view-space projection matrix captured from the parameter that
 * {@code GameRenderer.renderLevel} hands to {@code LevelRenderer.renderLevel}. That matrix
 * already has bob, portal-effect, nausea, screen-effect-scale and any other view-space
 * distortions baked in by MC, so a single capture covers every vanilla view effect.
 *
 * <p>Populated by {@link me.cortex.vulkium.mixin.camera.LevelRendererProjMixin} at that
 * method's HEAD. Consumed by {@code FrameDriver.onEndMain} which composes
 * {@code mvp = capturedProjection × viewRotation} — the viewRotation stays separate because
 * MC's LevelRenderer applies rotation downstream of this projection.
 *
 * <p>The class name is historical — it started as a bob-only capture hooked on
 * {@code GameRenderer.bobView/bobHurt}, but was broadened to cover all view-space effects.
 * Keeping the name for now to avoid mixin-path churn.
 */
public final class BobViewTap {
    private static final Matrix4f MATRIX = new Matrix4f();
    private static volatile boolean valid = false;

    private BobViewTap() {}

    /** Called at the HEAD of LevelRenderer.renderLevel with MC's final view-space projection. */
    public static void setProjection(Matrix4fc source) {
        synchronized (MATRIX) {
            MATRIX.set(source);
            valid = true;
        }
    }

    /** @return the captured projection copied into {@code dst}, or {@code false} if nothing's
     *  been captured yet (first frame before LevelRenderer.renderLevel has fired). */
    public static boolean read(Matrix4f dst) {
        if (!valid) return false;
        synchronized (MATRIX) {
            dst.set(MATRIX);
        }
        return true;
    }

    public static boolean hasValue() { return valid; }

    /** Invalidate so a subsequent read returns false. Not used on the hot path — the
     *  capture runs every frame, so the stored matrix stays fresh — but kept for explicit
     *  reset scenarios (init teardown, etc.). */
    public static void invalidate() {
        synchronized (MATRIX) {
            MATRIX.identity();
            valid = false;
        }
    }
}
