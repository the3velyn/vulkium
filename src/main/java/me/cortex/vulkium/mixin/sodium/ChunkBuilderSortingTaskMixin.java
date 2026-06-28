package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.managers.SectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.Sorter;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Captures Sodium's POV-driven translucent sort output and routes it into vulkium's arena
 * via {@code SectionManager.offerResort} → {@code TerrainUploader.resortTranslucent}.
 *
 * <p>Pairs with the existing {@link ChunkBuilderMeshingTaskMixin} (which captures vertex
 * data on fresh builds). The vertex bytes populate {@code translucentUnsortedCache} during
 * the next render-frame's ingest drain; this mixin's sorted index buffer is then applied
 * to that cache on the same or following frame's resort drain, permuting the cached
 * unsorted quads into POV-sorted arena bytes.
 *
 * <p>Sodium's sort path:
 *   {@code RenderSectionManager.scheduleSort} → ChunkBuilder queues SORT task →
 *   {@code ChunkBuilderSortingTask.execute} → {@code DynamicSorter.writeIndexBuffer} →
 *   returns {@code ChunkSortOutput}. The sorter's index buffer holds 6 uint32 indices
 *   per quad in the form {@code [4k+0, 4k+1, 4k+2, 4k+2, 4k+3, 4k+0]} where {@code k}
 *   walks the back-to-front quad order — same layout {@code TerrainUploader.resortTranslucent}
 *   already handles for the standalone-edition MC path.
 *
 * <p>If the sorter is a {@link net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.SharedIndexSorter}
 * ({@code getIndexBuffer() == null}), the section uses the standard quad-index pattern
 * with no POV-dependent ordering — nothing to do; vulkium's build-order draw is correct.
 *
 * <p>{@code remap = false} per the Sodium-mixin convention.
 */
@Mixin(value = ChunkBuilderSortingTask.class, remap = false)
public abstract class ChunkBuilderSortingTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/sodium");

    @Inject(method = "execute", at = @At("RETURN"))
    private void vulkium$captureSortOutput(CallbackInfoReturnable<ChunkSortOutput> cir) {
        ChunkSortOutput output = cir.getReturnValue();
        if (output == null || output.section == null) return;
        Sorter sorter = output.getSorter();
        if (sorter == null) return;
        NativeBuffer ib = sorter.getIndexBuffer();
        if (ib == null) return; // SharedIndexSorter case — no per-section data
        try {
            ByteBuffer src = ib.getDirectBuffer();
            int bytes = src.remaining();
            if (bytes <= 0) return;
            byte[] copy = new byte[bytes];
            src.duplicate().get(copy);
            long key = output.section.getPosition().asLong();
            SectionManager.get().offerResort(key, copy);
        } catch (Throwable t) {
            LOGGER.error("offerResort threw, sort dropped for this section", t);
        }
    }
}
