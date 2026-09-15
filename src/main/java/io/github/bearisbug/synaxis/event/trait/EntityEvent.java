package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.Event;

/**
 * Represents any event called on an {@link Entity}.
 */
public interface EntityEvent extends Event {

    /**
     * Gets the entity of this event.
     *
     * @return the entity
     */
    Entity getEntity();
}
