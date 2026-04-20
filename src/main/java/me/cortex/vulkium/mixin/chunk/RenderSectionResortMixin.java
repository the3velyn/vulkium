package me.cortex.vulkium.mixin.chunk;

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
            at = @At("HEAD"))
    private void vulkium$captureTranslucentResort(ChunkSectionLayer layer,
                                                   CompiledSectionMesh mesh,
                                                   ByteBuffer vertexBuffer,
                                                   ByteBuffer indexBuffer,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (layer != ChunkSectionLayer.TRANSLUCENT) return;
        if (vertexBuffer != null) return;          // not a pure resort
        if (indexBuffer == null) return;
        int remaining = indexBuffer.remaining();
        if (remaining <= 0) return;

        // Copy NOW — the ByteBuffer is about to be consumed and freed by MC's upload path.
        byte[] copy = new byte[remaining];
        indexBuffer.duplicate().get(copy);

        BlockPos origin = getRenderOrigin();
        long key = SectionPos.asLong(origin);

        SectionManager.get().offerResort(key, copy);
    }
}
