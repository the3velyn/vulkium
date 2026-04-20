package me.cortex.vulkium.blaze3d;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Stores view-space matrices captured from MC's render pipeline for vulkium's MVP composition.
 *
 * <p>Two capture points:
 * <ul>
 *   <li>{@link #setPose(Matrix4fc)} — {@code GameRenderer.bobHurt/bobView} mixin captures the
 *       PoseStack matrix after walking-bob + hurt-shake. Pose-only, no projection.</li>
 *   <li>{@link #setProjection(Matrix4fc)} — {@code LevelRenderer.render/renderLevel} mixin
 *       captures the FULL view-space projection argument. Already has bob + portal warp +
 *       nausea + screen-effect-scale + any mod-injected distortion baked in. This is the
 *       catch-all for every view-space effect; we prefer it when present.</li>
 * </ul>
 *
 * <p>FrameDriver.onEndMain checks {@link #readProjection} first (catch-all). If that's not
 * populated (mixin didn't apply on this runtime — e.g. because the method name / descriptor
 * didn't resolve), it falls back to {@link #readPose} composed with camState.projectionMatrix.
 */
public final class BobViewTap {
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static volatile boolean poseValid = false;
    private static volatile boolean projectionValid = false;

    private BobViewTap() {}

    public static void setPose(Matrix4fc source) {
        synchronized (POSE) {
            POSE.set(source);
            poseValid = true;
        }
    }

    public static void setProjection(Matrix4fc source) {
        synchronized (PROJECTION) {
            PROJECTION.set(source);
            projectionValid = true;
        }
    }

    public static boolean readPose(Matrix4f dst) {
        if (!poseValid) return false;
        synchronized (POSE) {
            dst.set(POSE);
        }
        return true;
    }

    public static boolean readProjection(Matrix4f dst) {
        if (!projectionValid) return false;
        synchronized (PROJECTION) {
            dst.set(PROJECTION);
        }
        return true;
    }

    /** Back-compat: legacy {@code read} maps to the pose matrix. */
    public static boolean read(Matrix4f dst) {
        return readPose(dst);
    }

    public static boolean hasValue() { return poseValid || projectionValid; }

    public static void invalidate() {
        synchronized (POSE) {
            POSE.identity();
            poseValid = false;
        }
    }
}
