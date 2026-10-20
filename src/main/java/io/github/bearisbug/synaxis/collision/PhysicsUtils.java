package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.Nullable;

public final class PhysicsUtils {
    /**
     * The vertical drag of a flying entity, in place of the vertical air resistance.
     */
    private static final double FLYING_VERTICAL_DRAG = 0.6;

    /**
     * Simulate the entity's movement physics
     * <p>
     * This is done by first attempting to move the entity forward with the
     * current velocity passed in. Then adjusting the velocity by applying
     * air resistance and friction.
     * <p>
     * The boolean parameters come in another order than those of
     * {@link #updateVelocity(Pos, Vec, Block.Getter, Aerodynamics, boolean, boolean, boolean, boolean)}.
     *
     * @param entityPosition the current entity position
     * @param entityVelocityPerTick the current entity velocity in blocks/tick
     * @param entityBoundingBox the current entity bounding box
     * @param worldBorder the world border colliding with the entity, applied only when {@code entityHasPhysics} is true
     * @param blockGetter the block getter to test block collisions against
     * @param aerodynamics the current entity aerodynamics
     * @param entityNoGravity true to apply no gravity, as {@link io.github.bearisbug.synaxis.entity.Entity#hasNoGravity()}
     * @param entityHasPhysics whether the entity has physics
     * @param entityOnGround whether the entity is on the ground
     * @param entityFlying whether the entity is flying
     * @param previousPhysicsResult the physics result from the previous simulation or null
     * @return a {@link PhysicsResult} containing the resulting physics state of this simulation
     */
    public static PhysicsResult simulateMovement(Pos entityPosition, Vec entityVelocityPerTick, BoundingBox entityBoundingBox,
                                                          WorldBorder worldBorder, Block.Getter blockGetter, Aerodynamics aerodynamics, boolean entityNoGravity,
                                                          boolean entityHasPhysics, boolean entityOnGround, boolean entityFlying, @Nullable PhysicsResult previousPhysicsResult) {
        final PhysicsResult physicsResult = entityHasPhysics ?
                CollisionUtils.handlePhysics(blockGetter, worldBorder, entityBoundingBox, entityPosition, entityVelocityPerTick, previousPhysicsResult, false) :
                CollisionUtils.blocklessCollision(entityPosition, entityVelocityPerTick);

        Pos newPosition = physicsResult.newPosition();
        Vec newVelocity = physicsResult.newVelocity();

        newVelocity = updateVelocity(newPosition, newVelocity, blockGetter, aerodynamics, !newPosition.samePoint(entityPosition), entityFlying, entityOnGround, entityNoGravity);

        if (physicsResult == previousPhysicsResult && newVelocity.samePoint(physicsResult.newVelocity())) {
            return physicsResult;
        }

        return physicsResult.withNewVelocity(newVelocity);
    }

    /**
     * Calculates an updated velocity for an entity
     * <p>
     * If the position has not changed, x and z are set to 0: a flying entity gets {@link Vec#ZERO}, any other
     * gets a y of 0 without gravity, else of {@code -gravity * verticalAirResistance}.
     * <p>
     * Otherwise, x and z are multiplied by the horizontal air resistance, and also by the friction of the block
     * below if the entity is on the ground. Without gravity y is kept as it is, else it becomes
     * {@code (y - gravity) * verticalAirResistance}; a flying entity uses a gravity of 0 and a vertical drag of 0.6.
     * <p>
     * The boolean parameters come in another order than those of
     * {@link #simulateMovement(Pos, Vec, BoundingBox, WorldBorder, Block.Getter, Aerodynamics, boolean, boolean, boolean, boolean, PhysicsResult)}.
     *
     * @param entityPosition the current entity position
     * @param currentVelocity the current entity velocity in blocks/tick
     * @param blockGetter the block getter to test block collisions against
     * @param aerodynamics the current entity aerodynamics
     * @param positionChanged whether the position changed for the entity
     * @param entityFlying whether the entity is flying
     * @param entityOnGround whether the entity is on the ground
     * @param entityNoGravity true to apply no gravity, as {@link io.github.bearisbug.synaxis.entity.Entity#hasNoGravity()}
     * @return the updated velocity, {@link Vec#ZERO} only if the position has not changed and the entity is flying
     */
    public static Vec updateVelocity(Pos entityPosition, Vec currentVelocity, Block.Getter blockGetter, Aerodynamics aerodynamics,
                                               boolean positionChanged, boolean entityFlying, boolean entityOnGround, boolean entityNoGravity) {
        if (!positionChanged) {
            if (entityFlying) return Vec.ZERO;
            return new Vec(0, entityNoGravity ? 0 : -aerodynamics.gravity() * aerodynamics.verticalAirResistance(), 0);
        }

        double drag = entityOnGround ? blockGetter.getBlock(entityPosition.sub(0, 0.5000001, 0)).friction() * aerodynamics.horizontalAirResistance()
                : aerodynamics.horizontalAirResistance();
        double gravity = entityFlying ? 0 : aerodynamics.gravity();
        double gravityDrag = entityFlying ? FLYING_VERTICAL_DRAG : aerodynamics.verticalAirResistance();

        double x = currentVelocity.x() * drag;
        double y = entityNoGravity ? currentVelocity.y() : (currentVelocity.y() - gravity) * gravityDrag;
        double z = currentVelocity.z() * drag;
        return new Vec(Math.abs(x) < Vec.EPSILON ? 0 : x, Math.abs(y) < Vec.EPSILON ? 0 : y, Math.abs(z) < Vec.EPSILON ? 0 : z);
    }

    private PhysicsUtils() {}
}
