package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

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
