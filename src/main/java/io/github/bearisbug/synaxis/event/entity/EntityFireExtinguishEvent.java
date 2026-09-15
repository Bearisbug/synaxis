package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

public class EntityFireExtinguishEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private final boolean natural;

    private boolean cancelled;

    public EntityFireExtinguishEvent(Entity entity, boolean natural) {
        this.entity = entity;
        this.natural = natural;
    }

    public boolean isNatural() {
        return natural;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
