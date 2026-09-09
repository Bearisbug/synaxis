package dev.gameengine.event.entity.projectile;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Entity;

public final class ProjectileCollideWithEntityEvent extends ProjectileCollideEvent {

    private final Entity target;

    public ProjectileCollideWithEntityEvent(
            Entity projectile,
            Pos position,
            Entity target
    ) {
        super(projectile, position);
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
