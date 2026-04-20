package me.cortex.vulkium.mixin.world;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The real 32-cap on chunk loading in MC 26.2's integrated server.
 *
 * <p>{@code DistanceManager.<init>} builds a {@code PlayerTicketTracker} with
 * {@code maxDistance=32}. That tracker is the source of {@code PLAYER_LOADING} tickets —
 * the ticket type that actually causes chunks to generate + reach FULL status. Since its
 * internal {@code FixedPlayerDistanceChunkTracker.maxDistance} is only 32, chunks beyond
 * that radius never enter the tracker and never get a ticket, so raising view distance
 * higher had no effect — generation silently capped at 32 chunks.
 *
 * <p>Matching nvidium-style view-distance extension on the integrated server requires
 * raising this constant too. 128 matches our other caps (Options sliders + ChunkMap
 * serverViewDistance ceiling).
 *
 * <p>This is the only {@code bipush 32} inside {@code DistanceManager.<init>} — 8 is
 * used for naturalSpawnChunkCounter, 32 is exclusively the PlayerTicketTracker arg.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerPlayerTicketMixin {

    @ModifyConstant(method = "<init>",
                    constant = @Constant(intValue = 32))
    private int vulkium$raisePlayerTicketTrackerMaxDistance(int original) {
        return 128;
    }
}
