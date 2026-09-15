package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

/**
 * Called when a velocity is applied to an entity using {@link Entity#setVelocity(Vec)}.
 */
public class EntityVelocityEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private Vec velocity;

    private boolean cancelled;

    public EntityVelocityEvent(Entity entity, Vec velocity) {
        this.entity = entity;
        this.velocity = velocity;
    }

    /**
     * Gets the enity who will be affected by {@link #getVelocity()}.
     *
     * @return the entity
     */
    @Override
    public Entity getEntity() {
        return entity;
    }

    /**
     * Gets the velocity which will be applied.
     *
     * @return the velocity
     */
    public Vec getVelocity() {
        return velocity;
    }

    /**
     * Changes the applied velocity.
     *
     * @param velocity the new velocity
     */
    public void setVelocity(Vec velocity) {
        this.velocity = velocity;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
