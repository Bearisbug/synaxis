package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.potion.Potion;

public class EntityPotionAddEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private final Potion potion;

    private boolean cancelled = false;

    public EntityPotionAddEvent(Entity entity, Potion potion) {
        this.entity = entity;
        this.potion = potion;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }

    /**
     * Returns the potion that was added.
     *
     * @return the added potion.
     */
    public Potion getPotion() {
        return potion;
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
