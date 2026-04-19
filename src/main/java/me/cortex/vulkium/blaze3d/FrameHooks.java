package me.cortex.vulkium.blaze3d;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Frame-time hook dispatch. Mixins into Mojang's Vulkan frame recording call the {@code fire*()}
 * methods at specific points in the command stream. Vulkium subsystems register via
 * {@link #register(Listener)} to record draws at the right moment.
 *
 * <p>All hooks fire on the render thread.
 */
public final class FrameHooks {
    public interface Listener {
        default void onFrameBegin(VkCommandBuffer cmd) {}
        default void beforeWorldTerrain(VkCommandBuffer cmd) {}
        default void beforeTranslucent(VkCommandBuffer cmd) {}
        default void onFrameEnd(VkCommandBuffer cmd) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private FrameHooks() {}

    public static void register(Listener listener) { LISTENERS.add(listener); }

    public static void unregister(Listener listener) { LISTENERS.remove(listener); }

    public static void fireOnFrameBegin(VkCommandBuffer cmd) {
        for (int i = 0; i < LISTENERS.size(); i++) LISTENERS.get(i).onFrameBegin(cmd);
    }

    public static void fireBeforeWorldTerrain(VkCommandBuffer cmd) {
        for (int i = 0; i < LISTENERS.size(); i++) LISTENERS.get(i).beforeWorldTerrain(cmd);
    }

    public static void fireBeforeTranslucent(VkCommandBuffer cmd) {
        for (int i = 0; i < LISTENERS.size(); i++) LISTENERS.get(i).beforeTranslucent(cmd);
    }

    public static void fireOnFrameEnd(VkCommandBuffer cmd) {
        for (int i = 0; i < LISTENERS.size(); i++) LISTENERS.get(i).onFrameEnd(cmd);
    }
}
