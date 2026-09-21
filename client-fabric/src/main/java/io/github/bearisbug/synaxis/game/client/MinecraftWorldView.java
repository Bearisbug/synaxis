package io.github.bearisbug.synaxis.game.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The adapter behind {@link ClientWorldView}: the whole of {@code REQ-M2-019} that a running game
 * is needed to verify.
 * <p>
 * <b>Both bodies are untested and must stay that way.</b> They reach {@code Minecraft.getInstance()},
 * which exists only inside a client, so no unit test can run them; they are kept to two statements
 * each so that reading them is as good as running them. Their real check is the end to end pass of
 * {@code docs/TEST.md} TC-GAME-047, which is manual and needs a screen.
 */
public final class MinecraftWorldView implements ClientWorldView {

    @Override
    public Vec3 renderPosition(int entityId) {
        final Minecraft client = Minecraft.getInstance();
        final ClientLevel level = client.level;
        if (level == null) return null;
        final Entity entity = level.getEntity(entityId);
        // The partial tick of the frame being drawn, which is what makes this the rendered
        // position rather than the last one a packet carried.
        return entity == null ? null
                : entity.getPosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }

    @Override
    public Vec3 groundHit(Vec3 origin, Vec3 direction, double maxDistance) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        final Vec3 end = origin.add(direction.normalize().scale(maxDistance));
        final BlockHitResult hit = level.clip(new ClipContext(origin, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;
    }
}
