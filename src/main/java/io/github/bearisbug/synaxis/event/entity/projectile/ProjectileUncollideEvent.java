package io.github.bearisbug.synaxis.event.entity.projectile;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

public final class ProjectileUncollideEvent implements EntityInstanceEvent {

    private final Entity projectile;

    public ProjectileUncollideEvent(Entity projectile) {
        this.projectile = projectile;
    }

    @Override
    public Entity getEntity() {
        return projectile;
    }

}
