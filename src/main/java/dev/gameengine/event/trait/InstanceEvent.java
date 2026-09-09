package dev.gameengine.event.trait;

import dev.gameengine.event.Event;
import dev.gameengine.instance.Instance;

/**
 * Represents any event targeting an {@link Instance}.
 */
public interface InstanceEvent extends Event {

    /**
     * Gets the instance.
     *
     * @return instance
     */
    Instance getInstance();
}
