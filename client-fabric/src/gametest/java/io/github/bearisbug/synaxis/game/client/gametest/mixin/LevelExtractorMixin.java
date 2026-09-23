package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import io.github.bearisbug.synaxis.game.client.gametest.cull.RenderedEntities;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records which entities a frame extracts for rendering into {@link RenderedEntities}.
 * {@code extractEntity} is only called from {@code extractVisibleEntities}, after an entity passed
 * every check that method makes, so its callers are exactly the frame's render list.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void synaxis$beginFrame(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
            LevelRenderState state, CallbackInfo ci) {
        RenderedEntities.beginFrame();
    }

    @Inject(method = "extractEntity", at = @At("HEAD"))
    private void synaxis$rendered(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        RenderedEntities.add(entity);
    }
}
