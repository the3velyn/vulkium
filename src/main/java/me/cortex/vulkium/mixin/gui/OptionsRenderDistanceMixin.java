package me.cortex.vulkium.mixin.gui;

import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Raises the max of MC's "Render Distance" and "Simulation Distance" sliders from
 * 32/16 to 128, via two scoped {@link ModifyArg}s on the
 * {@code OptionInstance.IntRange(int,int,boolean)} constructor in {@link Options}'s
 * {@code <init>}. Slices scope each rewrite to its relevant slider so we don't touch
 * unrelated IntRanges in the same method.
 *
 * <p>Why both: chunk streaming in MC 26.2's integrated server depends on simulation
 * distance as well as view distance — chunks past simulation distance stay at a
 * lower loaded-status that may not get sent to the client. Raising render alone
 * expands the client's frustum but chunks don't keep up.
 *
 * <p>Singleplayer only in practice: the integrated server honors this, but multiplayer
 * servers still clamp to their own settings.
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

    @ModifyArg(method = "<init>",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V"),
               index = 1,
               slice = @Slice(
                   from = @At(value = "CONSTANT", args = "stringValue=options.simulationDistance")
                   // No explicit `to`: slice extends to end of method. The simulationDistance's
                   // IntRange(IIZ) ctor is the first and only such call after that string.
               ))
    private int vulkium$extendSimulationDistanceMax(int originalMax) {
        return Math.max(originalMax, 128);
    }
}
