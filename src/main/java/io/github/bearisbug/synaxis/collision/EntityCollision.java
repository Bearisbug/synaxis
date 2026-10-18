package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.instance.EntityTracker;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class EntityCollision {
    static List<EntityCollisionResult> checkCollision(
            EntityTracker entityTracker,
            BoundingBox boundingBox, Point point, Vec entityVelocity,
            double extendRadius, Function<Entity, Boolean> entityFilter, @Nullable PhysicsResult physicsResult
    ) {
        double minimumRes = physicsResult != null ? physicsResult.collisionFraction() : Double.MAX_VALUE;

        List<EntityCollisionResult> result = new ArrayList<>();

        double maxDistance = Math.sqrt(boundingBox.height() * boundingBox.height() + boundingBox.depth() / 2 * boundingBox.depth() / 2 + boundingBox.width() / 2 * boundingBox.width() / 2);
        double projectileDistance = entityVelocity.length();

        entityTracker.nearbyEntities(point, extendRadius + maxDistance + projectileDistance, EntityTracker.Target.ENTITIES, e -> {
            if (!entityFilter.apply(e)) return;
            if (!e.hasEntityCollision()) return;

            // Overlapping with entity, math can't be done we return the entity
            if (e.getBoundingBox().intersectBox(e.getPosition().sub(point), boundingBox)) {
                var p = point.asPos();
                result.add(new EntityCollisionResult(p, e, Vec.ZERO, 0));
                return;
            }

            // Check collisions with entity
            final SweepResult sweep = new SweepResult(minimumRes, 0, 0, 0, null, 0, 0, 0, 0, 0, 0);
            final double percentage = RayUtils.boundingBoxIntersectionPercentage(
                    boundingBox, point, entityVelocity, e.getBoundingBox(), e.getPosition(), minimumRes, sweep);

            if (percentage < 1) {
                var p = point.asPos().add(entityVelocity.mul(percentage));
                // The normal of the hit face, against the movement on the axis it was hit on
                final Vec direction;
                if (sweep.normalX != 0) direction = new Vec(-Math.signum(entityVelocity.x()), 0, 0);
                else if (sweep.normalY != 0) direction = new Vec(0, -Math.signum(entityVelocity.y()), 0);
                else direction = new Vec(0, 0, -Math.signum(entityVelocity.z()));
                result.add(new EntityCollisionResult(p, e, direction, percentage));
            }
        });

        result.sort(null);
        return result;
    }
}
