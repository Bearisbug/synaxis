package dev.gameengine.event.entity;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.EntityInstanceEvent;

public class EntitySetFireEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private int ticks;

    private boolean cancelled;

    public EntitySetFireEvent(Entity entity, int ticks) {
        this.entity = entity;
        this.ticks = ticks;
    }

    public int getFireTicks() {
        return ticks;
    }

    public void setFireTicks(int ticks) {
        this.ticks = ticks;
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
