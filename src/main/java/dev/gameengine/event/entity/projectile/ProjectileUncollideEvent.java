package dev.gameengine.event.entity.projectile;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;

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
