package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The result of a physics simulation.
 * <p>
 * A world border collision records a collision axis and point without a shape. The
 * corresponding {@code collisionShapes} and {@code collisionShapePositions} entries stay
 * null even though the axis flag is set and the {@code collisionPoints} entry is present.
 *
 * @param newPosition the new position of the entity
 * @param newVelocity the new velocity of the entity
 * @param collisionX if the entity collided on the X axis
 * @param collisionY if the entity collided on the Y axis
 * @param collisionZ if the entity collided on the Z axis
 * @param originalDelta the velocity delta of the entity
 * @param collisionPoints the points where the entity collided
 * @param collisionShapes the shapes the entity collided with
 * @param collisionShapePositions the block positions of the shapes the entity collided with
 * @param collisionFraction fraction accepted by the final collision sweep
 */
@ApiStatus.Experimental
public record PhysicsResult(
        Pos newPosition,
        Vec newVelocity,
        boolean collisionX,
        boolean collisionY,
        boolean collisionZ,
        Vec originalDelta,
        @Nullable Vec[] collisionPoints,
        @Nullable Shape[] collisionShapes,
        @Nullable BlockVec[] collisionShapePositions,
        double collisionFraction
) {
    /**
     * A result without any collision: the entity moved to {@code newPosition} and keeps {@code delta} as velocity.
     */
    static PhysicsResult noCollision(Pos newPosition, Vec delta) {
        return new PhysicsResult(newPosition, delta, false, false, false, delta,
                BlockCollision.NO_COLLISION_POINTS, BlockCollision.NO_COLLISION_SHAPES,
                BlockCollision.NO_COLLISION_SHAPE_POSITIONS, Double.MAX_VALUE);
    }

    /**
     * Gets if the entity is on the ground: it collided on the Y axis while moving down.
     *
     * @return true if the entity is on the ground
     */
    public boolean isOnGround() {
        return collisionY && originalDelta.y() < 0;
    }

    /**
     * Gets if the entity collided on any axis.
     *
     * @return true if the entity collided
     */
    public boolean hasCollision() {
        return collisionX || collisionY || collisionZ;
    }

    /**
     * Creates a copy of this result with another velocity, every other component is kept.
     *
     * @param newVelocity the new velocity of the entity
     * @return the result with the new velocity
     */
    public PhysicsResult withNewVelocity(Vec newVelocity) {
        return new PhysicsResult(newPosition, newVelocity, collisionX, collisionY, collisionZ, originalDelta,
                collisionPoints, collisionShapes, collisionShapePositions, collisionFraction);
    }
}
