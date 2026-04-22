package me.cortex.vulkium.mixin.chunk;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.VulkiumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;

/**
 * Short-circuits {@link LevelRenderer#prepareChunkRenders} when vulkium is actively drawing
 * terrain. This is the CPU-side twin of {@link ChunkSectionsToRenderMixin} (which cancels the
 * final {@code renderGroup} draw): the prep method is where MC walks {@code visibleSections}
 * and writes per-section data into {@code DynamicUniforms.writeChunkSections} — O(visible
 * section count) work per frame, and it's the source of the 0.3x-FPS regression the user
 * observed after the first F3+A on world join.
 *
 * <h3>Why F3+A triggers the drop (and not the initial world load)</h3>
 * <p>On initial load, MC's {@code visibleSections} list grows gradually as chunks finish
 * compiling. The per-frame cost ramps with it and the user sees steady-state perf that
 * scales with how many chunks are currently in the list. After F3+A, MC has ALL chunks
 * compiled and added to the visible list simultaneously — per-frame prep cost jumps to its
 * maximum. The cost stays high because MC doesn't shrink its section list.
 * <p>A matching symptom: joining with vulkium off, letting MC fully compile, then toggling
 * vulkium on. Same dynamic — MC's list is full when vulkium activates, so prep is at max.
 *
 * <h3>Stub construction</h3>
 * <p>Returns a {@link ChunkSectionsToRender} with:
 * <ul>
 *   <li>textureView = MC's current main render target color view (used downstream by
 *       {@code addMainPass}; our existing chunk-tap mixin still captures from here on the
 *       first {@code renderGroup} call).</li>
 *   <li>drawGroupsPerLayer = empty per-layer map. {@code renderGroup} would iterate it and
 *       issue zero draws, but our {@code renderGroup} mixin cancels at HEAD anyway — doubly
 *       safe.</li>
 *   <li>maxIndicesRequired = 0. {@code renderGroup}'s first act is to read this and bail if
 *       zero, so even if the renderGroup cancel-at-HEAD path ever failed to take, there'd
 *       be no real draw.</li>
 *   <li>chunkSectionInfos = empty array. Never dereferenced because renderGroup doesn't run
 *       on the stub.</li>
 * </ul>
 *
 * <h3>Gate</h3>
 * <p>Only short-circuits when {@link Vulkium#isEnabled()} and
 * {@code VulkiumConfig.drawTerrain} are both true — same gate as
 * {@link ChunkSectionsToRenderMixin}. Toggle-off and drawTerrain=false paths run MC's
 * original prep so its terrain renders normally.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPrepareChunkRendersMixin {

    @Inject(
        method = "prepareChunkRenders",
        at = @At("HEAD"),
        cancellable = true)
    private void vulkium$shortCircuitPrepareChunkRenders(Matrix4fc projMatrix,
                                                         CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        if (!Vulkium.isEnabled() || !VulkiumConfig.get().drawTerrain) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.gameRenderer.mainRenderTarget() == null) return;
        GpuTextureView textureView = mc.gameRenderer.mainRenderTarget().getColorTextureView();
        if (textureView == null) return;

        cir.setReturnValue(new ChunkSectionsToRender(
            textureView,
            new EnumMap<>(ChunkSectionLayer.class),
            0,
            new GpuBufferSlice[0]));
    }
}
