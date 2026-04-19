package me.cortex.vulkium.blaze3d;

/**
 * Shared state for Mojang's captured main color-attachment VkFormat. Populated at first
 * {@code ChunkSectionsToRender.renderGroup} invocation by
 * {@code mixin.chunk.ChunkSectionsToRenderMixin}; consumed by {@code FrameDriver} when it
 * builds its {@code SecondaryRecorder.InheritanceSpec} so format matches Mojang's primary.
 *
 * <p>Mixin classes can't hold non-private static fields, hence this separate carrier.
 */
public final class MojangColorFormat {
    private static volatile int vkFormat = 0;

    private MojangColorFormat() {}

    /** 0 until the mixin captures the real format. */
    public static int get() { return vkFormat; }

    public static void set(int vk) { vkFormat = vk; }
}
