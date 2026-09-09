package dev.gameengine.event.entity;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;

public class EntityDeathEvent implements EntityInstanceEvent {

    // TODO cause
    private final Entity entity;

    public EntityDeathEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
