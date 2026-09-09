package dev.gameengine.event.entity.projectile;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Entity;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.EntityInstanceEvent;
import dev.gameengine.event.trait.RecursiveEvent;

class ProjectileCollideEvent implements EntityInstanceEvent, CancellableEvent, RecursiveEvent {

    private final Entity projectile;
    private final Pos position;
    private boolean cancelled;

    protected ProjectileCollideEvent(Entity projectile, Pos position) {
        this.projectile = projectile;
        this.position = position;
    }

    @Override
    public Entity getEntity() {
        return projectile;
    }

    public Pos getCollisionPosition() {
        return position;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }
}
