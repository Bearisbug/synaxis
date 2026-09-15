package io.github.bearisbug.synaxis.event.entity.projectile;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;

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
