package me.cortex.vulkium.blaze3d;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Stores the bob-and-hurt pose matrix captured from {@code GameRenderer.bobHurt/bobView} via
 * {@link me.cortex.vulkium.mixin.camera.GameRendererBobMixin}. FrameDriver composes MVP as
 * {@code projection × pose × viewRotation}, matching vanilla's composition in
 * {@code renderLevel} — {@code projCopy.mul(pose.last().pose())} then the downstream
 * {@code LevelRenderer.renderLevel} receives that already-composed projection.
 *
 * <p>Does NOT yet cover portal-effect warp or nausea distortion; those get applied further
 * downstream in {@code GameRenderer.renderLevel} after the bobs. Both the @Inject and
 * @ModifyArg attempts to catch the final fully-composed projection on MC 26.2 Fabric dev
 * failed mixin target resolution ("Scanned 0 target(s)" despite byte-accurate descriptors).
 * Treated as a follow-up.
 */
public final class BobViewTap {
    private static final Matrix4f MATRIX = new Matrix4f();
    private static volatile boolean valid = false;

    private BobViewTap() {}

    /** Called at the TAIL of bobHurt / bobView with the mutated PoseStack's top matrix. */
    public static void setPose(Matrix4fc source) {
        synchronized (MATRIX) {
            MATRIX.set(source);
            valid = true;
        }
    }

    /** @return the captured matrix copied into {@code dst}, or {@code false} if nothing's
     *  been captured yet (first frame before any bob hook fired). */
    public static boolean read(Matrix4f dst) {
        if (!valid) return false;
        synchronized (MATRIX) {
            dst.set(MATRIX);
        }
        return true;
    }

    public static boolean hasValue() { return valid; }

    public static void invalidate() {
        synchronized (MATRIX) {
            MATRIX.identity();
            valid = false;
        }
    }
}
