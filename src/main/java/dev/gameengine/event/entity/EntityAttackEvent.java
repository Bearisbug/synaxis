package dev.gameengine.event.entity;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;

/**
 * Called when a player does a left click on an entity or with
 * {@link dev.gameengine.entity.EntityCreature#attack(Entity)}.
 */
public class EntityAttackEvent implements EntityInstanceEvent {

    private final Entity entity;
    private final Entity target;

    public EntityAttackEvent(Entity source, Entity target) {
        this.entity = source;
        this.target = target;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }

    /**
     * {@return the target of the attack}
     */
    public Entity getTarget() {
        return target;
    }
}
