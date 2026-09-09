package dev.gameengine.event.instance;

import dev.gameengine.event.trait.InstanceEvent;
import dev.gameengine.instance.Instance;

/**
 * Called when an instance is unregistered
 */
public class InstanceUnregisterEvent implements InstanceEvent {
    private final Instance instance;

    public InstanceUnregisterEvent(Instance instance) {
        this.instance = instance;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }
}
