package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

/**
 * Called when the fire of a {@link io.github.bearisbug.synaxis.entity.LivingEntity} goes out: naturally
 * when its fire ticks run out ({@link #isNatural()}), or through
 * {@link io.github.bearisbug.synaxis.entity.LivingEntity#setFireTicks(int)} with 0.
 * <p>
 * Cancelling it keeps the entity burning: a natural extinguishing is asked again every tick, a call
 * to {@code setFireTicks(0)} leaves the fire ticks as they were. To burn for good, set a large value
 * in {@link EntitySetFireEvent} or with {@code setFireTicks} instead. A respawning player is put out
 * without this event.
 */
public class EntityFireExtinguishEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private final boolean natural;

    private boolean cancelled;

    public EntityFireExtinguishEvent(Entity entity, boolean natural) {
        this.entity = entity;
        this.natural = natural;
    }

    /**
     * Gets whether the fire ticks ran out, rather than a call to {@code setFireTicks(0)}.
     *
     * @return true if the fire went out by itself
     */
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
