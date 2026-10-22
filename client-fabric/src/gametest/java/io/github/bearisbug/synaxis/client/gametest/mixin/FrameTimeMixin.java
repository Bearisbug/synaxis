package io.github.bearisbug.synaxis.client.gametest.mixin;

import io.github.bearisbug.synaxis.client.gametest.light.FrameTimes;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The start of every frame for the {@code light-frames} mode (gametest light/FrameTimes): one frame is
 * one {@code Minecraft#runTick}. Mixin configs name their classes under one package, so the class is
 * here and what it feeds is in {@code gametest/light/}.
 */
@Mixin(Minecraft.class)
abstract class FrameTimeMixin {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void synaxis$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        FrameTimes.frame(System.nanoTime());
    }
}
