package io.github.bearisbug.synaxis.event.entity.projectile;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;

/**
 * Called when a projectile moving through a tick hits a living entity.
 * <p>
 * The engine neither stops nor removes the projectile on a hit, it keeps flying. For a hit to count once, the
 * listener removes the projectile or changes its state; otherwise the event is called again at each tick the
 * projectile still overlaps the target. Cancelling ignores the target for the rest of this tick.
 */
public final class ProjectileCollideWithEntityEvent extends ProjectileCollideEvent {

    private final Entity target;

    public ProjectileCollideWithEntityEvent(
            Entity projectile,
            Pos position,
            Entity target
    ) {
        super(projectile, position);
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
