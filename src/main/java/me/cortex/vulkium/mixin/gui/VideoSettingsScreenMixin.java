package me.cortex.vulkium.mixin.gui;

import me.cortex.vulkium.gui.VulkiumOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Appends a "Vulkium Options..." button to the bottom of MC's Video Settings screen. Clicking
 * opens {@link VulkiumOptionsScreen} as a sub-screen; closing that returns here.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {

    private VideoSettingsScreenMixin(Screen parent, Options options, Component title) {
        super(parent, options, title);
        throw new AssertionError("mixin ctor — never called");
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void vulkium$appendOptionsButton(CallbackInfo ci) {
        Button button = Button.builder(
                Component.literal("Vulkium Options..."),
                b -> Minecraft.getInstance().setScreenAndShow(
                    VulkiumOptionsScreen.open((VideoSettingsScreen)(Object)this, this.options)))
            .width(200)
            .build();
        this.list.addSmall(java.util.List.of(button));
    }
}
