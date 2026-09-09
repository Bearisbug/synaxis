package dev.gameengine.event.instance;

import dev.gameengine.event.trait.InstanceEvent;
import dev.gameengine.instance.Instance;

/**
 * Called when an instance is registered
 */
public class InstanceRegisterEvent implements InstanceEvent {
    private final Instance instance;

    public InstanceRegisterEvent(Instance instance) {
        this.instance = instance;
    }

    @Override
    public Instance getInstance() {
        return instance;
    }
}
