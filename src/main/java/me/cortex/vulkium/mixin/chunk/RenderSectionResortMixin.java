package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Captures MC's POV-driven translucent resort output for vulkium's arena.
 *
 * <p>When the camera crosses a sub-chunk boundary MC's {@code ResortTransparencyTask.doTask}
 * runs on a worker thread. It doesn't go through {@code SectionCompiler.compile} (so our
 * primary section-capture mixin doesn't fire). Instead it:
 * <ol>
 *   <li>Pulls the section's {@code MeshData$SortState} from {@code CompiledSectionMesh}.</li>
 *   <li>Builds a fresh 6-indices-per-quad sorted index buffer at the new POV.</li>
 *   <li>Calls {@code RenderSection.addSectionBuffersToUberBuffer(TRANSLUCENT, mesh,
 *       nullVertexBuffer, newIndexBuffer)} to upload only the index buffer to MC's uber-buffer.</li>
 * </ol>
 *
 * <p>We hook that last call and sniff the new index buffer. The (vertex=null, index!=null,
 * layer=TRANSLUCENT) combo uniquely identifies the resort path — initial upload always has
 * a non-null vertexBuffer. We copy the index bytes on the worker thread (MC closes the
 * buffer immediately after this method returns) and queue a {@link SectionManager} resort
 * job; the render thread later permutes our cached unsorted vertex bytes into the new sort
 * order and re-uploads the translucent sub-range of the arena.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionResortMixin {

    /** RenderSection already declares getRenderOrigin as public — shadow to get at it
     *  without an access-widener. */
    @Shadow public abstract BlockPos getRenderOrigin();

    @Inject(method = "addSectionBuffersToUberBuffer",
            at = @At("HEAD"),
            cancellable = true)
    private void vulkium$interceptUberBufferUpload(ChunkSectionLayer layer,
                                                    CompiledSectionMesh mesh,
                                                    ByteBuffer vertexBuffer,
                                                    ByteBuffer indexBuffer,
                                                    CallbackInfoReturnable<Boolean> cir) {
        // Resort-only calls (translucent, vertex=null, index!=null): capture for vulkium's
        // arena then let MC proceed — MC's TranslucentSort uses the same buffer for its own
        // post-sorted translucent pass, so we don't cancel. Only snapshot the bytes; the
        // ByteBuffer is consumed right after we return.
        if (layer == ChunkSectionLayer.TRANSLUCENT && vertexBuffer == null && indexBuffer != null) {
            int remaining = indexBuffer.remaining();
            if (remaining > 0) {
                byte[] copy = new byte[remaining];
                indexBuffer.duplicate().get(copy);
                BlockPos origin = getRenderOrigin();
                SectionManager.get().offerResort(SectionPos.asLong(origin), copy);
            }
            return;
        }

        // Initial-upload path (vertexBuffer != null): MC would memcpy this into its own
        // uber-buffer. Vulkium already captured the same bytes via SectionCompilerMixin →
        // SectionManager, so MC's upload is pure duplication. Skip it when vulkium is
        // actively drawing terrain — saves a full mesh memcpy + GPU upload per section
        // compile, plus a chunk of GPU memory MC would otherwise keep populated.
        //
        // CompiledSectionMesh's MC-side bookkeeping (the `uploaded` flag etc.) would
        // normally be set inside this method. Returning `true` from the callback makes
        // the caller believe the upload succeeded, so MC's state stays consistent.
        if (vertexBuffer != null
                && Vulkium.isEnabled()
                && VulkiumConfig.get().drawTerrain) {
            cir.setReturnValue(true);
        }
    }
}
