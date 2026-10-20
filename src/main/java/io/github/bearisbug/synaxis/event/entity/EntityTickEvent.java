package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

/**
 * Called when an entity ticks itself.
 * <p>
 * Called once each time the entity runs the body of its tick, on the tick thread that owns the entity, after
 * {@link Entity#update(long)} and before the expired effects are handled. A tick the entity sits out, because it is
 * beyond the simulation distance or its {@link io.github.bearisbug.synaxis.entity.DormancyPolicy} skips it, calls
 * none. Every call is a new event.
 */
public class EntityTickEvent implements EntityInstanceEvent {

    private final Entity entity;

    public EntityTickEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
