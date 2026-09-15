package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called by an Instance when an entity is removed from it.
 */
public class RemoveEntityFromInstanceEvent implements EntityInstanceEvent {
    private final Instance instance;
    private final Entity entity;

    public RemoveEntityFromInstanceEvent(Instance instance, Entity entity) {
        this.instance = instance;
        this.entity = entity;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the entity being removed.
     *
     * @return entity being removed
     */
    @Override
    public Entity getEntity() {
        return entity;
    }
}
