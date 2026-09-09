package dev.gameengine.event.entity;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;

/**
 * Called when an entity ticks itself.
 * Same event instance used for all tick events for the same entity.
 */
public class EntityTickEvent implements EntityInstanceEvent {

    private final Entity entity;

    public EntityTickEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
