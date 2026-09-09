package dev.gameengine.event.trait;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.Event;

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
