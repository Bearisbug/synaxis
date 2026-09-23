package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import io.github.bearisbug.synaxis.game.client.gametest.s5.MouseCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** S5's MouseHandler input path, see {@link MouseCapture}. Screens keep vanilla handling. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Unique private boolean synaxis$down;

    @Shadow public abstract double getScaledXPos(com.mojang.blaze3d.platform.Window window);

    @Shadow public abstract double getScaledYPos(com.mojang.blaze3d.platform.Window window);

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void synaxis$keepReleased(CallbackInfo ci) {
        if (MouseCapture.active) ci.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void synaxis$captureButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (!MouseCapture.active || minecraft.gui.screen() != null) return;
        synaxis$down = action == 1;
        MouseCapture.events.add((synaxis$down ? "press " : "release ") + info.button() + " " + synaxis$xy());
        ci.cancel();
    }

    @Inject(method = "onMove", at = @At("TAIL"))
    private void synaxis$captureMove(long handle, double x, double y, CallbackInfo ci) {
        if (MouseCapture.active && synaxis$down && minecraft.gui.screen() == null)
            MouseCapture.events.add("drag " + synaxis$xy());
    }

    @Unique
    private String synaxis$xy() {
        return String.format("%.1f,%.1f", getScaledXPos(minecraft.getWindow()), getScaledYPos(minecraft.getWindow()));
    }
}
