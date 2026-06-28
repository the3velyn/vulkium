package me.cortex.vulkium.mixin.sodium;

import me.cortex.vulkium.gui.VulkiumOptionsScreen;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Vulkium Options..." button to Sodium's video-settings screen so users who installed
 * Sodium (which replaces MC's vanilla VideoSettingsScreen with its own) still have a path
 * into vulkium's config UI. Pattern mirrors {@code me.cortex.vulkium.mixin.gui.VideoSettingsScreenMixin}
 * which does the same for vanilla MC's screen.
 *
 * <p>The button sits at the bottom-left of the Sodium screen — Sodium's apply/undo/close
 * cluster is bottom-right, the page list takes the left side, and the bottom-left corner
 * is empty in every layout mode (stacked-vertical, horizontal, narrow). Absolute
 * positioning rather than weaving into {@code rebuildActionButtons} keeps us out of
 * Sodium's layout-math chain — that method is recomputed on every {@code rebuild()} so
 * any precise positioning would have to recompute too, with risk of overlap if Sodium's
 * layout constants change.
 *
 * <p>Re-added per {@code rebuild()} call so it survives Sodium's clearWidgets+rebuild
 * cycle on resize / page switch.
 *
 * <p>{@code remap = false} per the Sodium-mixin convention.
 */
@Mixin(value = VideoSettingsScreen.class, remap = false)
public abstract class VideoSettingsScreenMixin extends Screen {

    private VideoSettingsScreenMixin(Component title) {
        super(title);
        throw new AssertionError("mixin ctor — never called");
    }

    @Inject(method = "rebuild", at = @At("TAIL"), remap = false)
    private void vulkium$appendOptionsButton(CallbackInfo ci) {
        int buttonW = 150;
        int buttonH = 20;
        int margin = 6;
        int x = margin;
        int y = this.height - buttonH - margin;
        Button button = Button.builder(
                Component.literal("Vulkium Options..."),
                b -> Minecraft.getInstance().setScreenAndShow(
                    VulkiumOptionsScreen.open((Screen) (Object) this,
                                              Minecraft.getInstance().options)))
            .bounds(x, y, buttonW, buttonH)
            .build();
        this.addRenderableWidget(button);
    }
}
