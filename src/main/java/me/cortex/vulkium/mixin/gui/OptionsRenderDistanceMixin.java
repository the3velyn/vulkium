package me.cortex.vulkium.mixin.gui;

import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Raises the max of MC's built-in "Render Distance" slider from 32 (vanilla) / 16 (non-far)
 * to 128. Works by {@link ModifyArg}-ing the {@code max} argument of the
 * {@code OptionInstance.IntRange(int,int,boolean)} constructor in {@link Options}'s
 * {@code <init>}, scoped via a {@link Slice} between the {@code "options.renderDistance"}
 * string constant and the {@code "options.simulationDistance"} one so we only touch the
 * RD slider's range (there are many other IntRanges in the same method).
 *
 * <p>Singleplayer only in practice: the integrated server honors this and streams chunks
 * out to 128, but multiplayer servers still clamp to their own view-distance setting.
 *
 * <p>Using {@link Math#max} preserves MC's own max if it's ever bumped in a future snapshot
 * (so a 26.3-snapshot that ships a native 256-RD slider wouldn't get regressed to 128).
 */
@Mixin(Options.class)
public abstract class OptionsRenderDistanceMixin {

    @ModifyArg(method = "<init>",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V"),
               index = 1,
               slice = @Slice(
                   from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance"),
                   to   = @At(value = "CONSTANT", args = "stringValue=options.simulationDistance")
               ))
    private int vulkium$extendRenderDistanceMax(int originalMax) {
        return Math.max(originalMax, 128);
    }
}
