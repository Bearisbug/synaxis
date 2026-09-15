package io.github.bearisbug.synaxis.event.entity.projectile;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.RecursiveEvent;

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
