package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import io.github.bearisbug.synaxis.game.client.gametest.ray.ScreenRay;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two per-frame points of {@link ScreenRay}: {@code update} is where the frame's camera is
 * placed, {@code extract} the first thing after it. Screenshots of the gametest API call the same
 * pair, with the partial tick they were asked for.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {

    @Shadow @Final private Camera mainCamera;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "update", at = @At("HEAD"))
    private void synaxis$panAnchor(DeltaTracker deltaTracker, CallbackInfo ci) {
        ScreenRay.beforeCameraUpdate(mainCamera, deltaTracker);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void synaxis$screenRay(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ScreenRay.afterCameraUpdate(minecraft, deltaTracker);
    }
}
