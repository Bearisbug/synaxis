package io.github.bearisbug.synaxis.event.instance;

import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * Called when an instance is unregistered.
 * <p>
 * Called on the thread that calls {@link io.github.bearisbug.synaxis.instance.InstanceManager#unregisterInstance},
 * before the instance is unregistered and its chunks unloaded, without holding the monitor of the instance.
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
