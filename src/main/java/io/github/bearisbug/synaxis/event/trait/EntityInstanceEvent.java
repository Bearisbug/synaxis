package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Represents an {@link EntityEvent} which happen in {@link Entity#getInstance()}.
 * Useful if you need to listen to entity events happening in its instance.
 * <p>
 * Be aware that the entity's instance must be non-null.
 */
public interface EntityInstanceEvent extends EntityEvent, InstanceEvent {
    @Override
    default Instance getInstance() {
        final Instance instance = getEntity().getInstance();
        assert instance != null : "EntityInstanceEvent is only supported on events where the entity's instance is non-null!";
        return instance;
    }
}
