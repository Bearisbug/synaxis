package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

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
