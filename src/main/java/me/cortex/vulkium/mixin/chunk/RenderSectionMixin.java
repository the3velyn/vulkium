package me.cortex.vulkium.mixin.chunk;

import me.cortex.vulkium.Vulkium;
import me.cortex.vulkium.managers.SectionManager;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges MC's RenderSection lifecycle into vulkium's live-section table.
 *
 * <p><b>No eviction on setSectionNode/reset.</b> MC's SectionRenderDispatcher keeps a fixed-size
 * array of RenderSections that get reassigned to different chunks as the player moves — the
 * rotating-cache pattern. setSectionNode(newNode) starts by calling reset() internally and then
 * stores the new node. If we evicted the old node on either setSectionNode HEAD or reset HEAD,
 * we'd nuke arena/region state for a chunk that's typically STILL loaded and just got handed
 * off to a different RenderSection. The "new" RenderSection for the same chunk then has to
 * re-compile, and the chunk flickers invisible for those frames — visible as disappearing
 * chunks that "come back" after a moment (the hole in 2026-04-20_03.49.10.png).
 *
 * <p>Consequence: our live table retains entries for chunks that are genuinely unloaded (out
 * of RD), leaking memory slowly. Acceptable for now; a targeted cleanup pass keyed on
 * {@code ClientLevel.hasChunk(cx, cz)} can evict genuinely-unloaded entries on an idle tick.
 *
 * <p>Block edits and POV re-sorts still refresh live entries via their own compile path — MC
 * invokes SectionCompiler.compile on the worker, our SectionCompilerMixin plumbs the key into
 * the thread-local, CompiledSectionMeshMixin captures the mesh, and SectionManager.ingest
 * replaces the entry via live.put (prev != null → freeEntry). So updates still land.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {

    @Shadow
    private volatile long sectionNode;

    // Intentionally empty — see class-level doc. Retained as a no-op anchor so that a future
    // selective-eviction strategy (e.g. on true chunk unload) has a placeholder to extend.
}
