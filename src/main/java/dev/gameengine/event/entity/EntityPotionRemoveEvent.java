package dev.gameengine.event.entity;

import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.EntityInstanceEvent;
import dev.gameengine.potion.Potion;

public class EntityPotionRemoveEvent implements EntityInstanceEvent {

    private final Entity entity;
    private final Potion potion;

    public EntityPotionRemoveEvent(Entity entity, Potion potion) {
        this.entity = entity;
        this.potion = potion;
    }

    /**
     * Returns the potion that was removed.
     *
     * @return the removed potion.
     */
    public Potion getPotion() {
        return potion;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
