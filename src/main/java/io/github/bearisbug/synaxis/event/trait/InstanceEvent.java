package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.instance.Instance;

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
