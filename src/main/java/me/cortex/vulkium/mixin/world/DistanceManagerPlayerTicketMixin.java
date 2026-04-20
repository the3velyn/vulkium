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
 * the ticket type that actually causes chunks to generate + reach FULL status.
 *
 * <p><b>Byte-overflow caveat:</b> the tracker's parent class ({@code FixedPlayerDistanceChunkTracker})
 * stores per-chunk distance/level values in a {@code Long2ByteMap}, with {@code defaultReturnValue}
 * initialized to {@code (byte)(maxDistance + 2)}. At maxDistance=128, {@code 130} truncates to the
 * signed byte {@code -126}, which corrupts the tracker's "untracked / out-of-range" sentinel —
 * observed symptom: a 3×3 chunk patch centered on the player's initial spawn that doesn't follow
 * the player. Stay safely inside byte range: {@code 120} is the biggest safe value that leaves the
 * {@code +2} default still positive ({@code 122}).
 *
 * <p>This is the only {@code bipush 32} inside {@code DistanceManager.<init>} — 8 is
 * used for naturalSpawnChunkCounter, 32 is exclusively the PlayerTicketTracker arg.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerPlayerTicketMixin {

    @ModifyConstant(method = "<init>",
                    constant = @Constant(intValue = 32))
    private int vulkium$raisePlayerTicketTrackerMaxDistance(int original) {
        return 120;
    }
}
