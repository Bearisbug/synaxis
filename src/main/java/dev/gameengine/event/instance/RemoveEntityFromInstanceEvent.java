package dev.gameengine.event.instance;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;
import dev.gameengine.instance.Instance;

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
