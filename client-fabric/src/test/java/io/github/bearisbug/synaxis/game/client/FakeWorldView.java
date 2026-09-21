package io.github.bearisbug.synaxis.game.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * The stand in for the two Minecraft questions of {@link ClientWorldView}.
 * <p>
 * Everything {@code REQ-M2-019} decides sits on this side of that interface, so a map of entity
 * positions and one ground point are all a test needs to drive picking, selection and ordering
 * without a client.
 */
final class FakeWorldView implements ClientWorldView {

    private final Map<Integer, Vec3> positions = new HashMap<>();
    private Vec3 ground;
    private Vec3 lastGroundOrigin;
    private Vec3 lastGroundDirection;

    FakeWorldView at(int entityId, double x, double y, double z) {
        positions.put(entityId, new Vec3(x, y, z));
        return this;
    }

    /** What a world switch or a chunk unload does to an entity this client was told about. */
    FakeWorldView forget(int entityId) {
        positions.remove(entityId);
        return this;
    }

    FakeWorldView ground(Vec3 point) {
        this.ground = point;
        return this;
    }

    Vec3 lastGroundOrigin() {
        return lastGroundOrigin;
    }

    Vec3 lastGroundDirection() {
        return lastGroundDirection;
    }

    @Override
    public Vec3 renderPosition(int entityId) {
        return positions.get(entityId);
    }

    @Override
    public Vec3 groundHit(Vec3 origin, Vec3 direction, double maxDistance) {
        this.lastGroundOrigin = origin;
        this.lastGroundDirection = direction;
        return ground;
    }
}
