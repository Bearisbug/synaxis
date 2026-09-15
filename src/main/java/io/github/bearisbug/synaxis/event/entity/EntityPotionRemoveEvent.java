package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.potion.Potion;

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
