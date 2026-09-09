package dev.gameengine.event.instance;

import dev.gameengine.event.trait.InstanceEvent;
import dev.gameengine.instance.Instance;

/**
 * Called when an instance processes a tick.
 */
public class InstanceTickEvent implements InstanceEvent {

    private final Instance instance;
    private final int duration;

    public InstanceTickEvent(Instance instance, long time, long lastTickAge) {
        this.instance = instance;
        this.duration = (int) (time - lastTickAge);
    }

    @Override
    public Instance getInstance() {
        return instance;
    }

    /**
     * Gets the duration of the tick in ms.
     *
     * @return the duration
     */
    public int getDuration() {
        return duration;
    }
}