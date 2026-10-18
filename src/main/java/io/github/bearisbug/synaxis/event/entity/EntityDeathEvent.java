package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

/**
 * Called by {@link io.github.bearisbug.synaxis.entity.LivingEntity#kill()} once the entity is dead.
 * <p>
 * Healing the entity back above 0 from a listener cancels the death: the entity is alive again. The
 * death animation status already sent and the passengers already dismounted stay so; an
 * {@link io.github.bearisbug.synaxis.entity.EntityCreature} is then not removed, a player gets no
 * death screen and no death message.
 */
public class EntityDeathEvent implements EntityInstanceEvent {

    // TODO cause
    private final Entity entity;

    public EntityDeathEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
