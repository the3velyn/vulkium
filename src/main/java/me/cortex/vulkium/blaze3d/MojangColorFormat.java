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
    private static volatile int attachmentWidth = 0;
    private static volatile int attachmentHeight = 0;
    private static volatile long vkImageView = 0L;

    private MojangColorFormat() {}

    /** 0 until the mixin captures the real format. */
    public static int get() { return vkFormat; }
    public static int width()  { return attachmentWidth; }
    public static int height() { return attachmentHeight; }
    /** 0 until the mixin captures Mojang's color VkImageView handle. */
    public static long imageView() { return vkImageView; }

    public static void set(int vk, int w, int h, long view) {
        vkFormat = vk;
        attachmentWidth = w;
        attachmentHeight = h;
        vkImageView = view;
    }
}
