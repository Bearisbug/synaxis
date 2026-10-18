package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityProjectile;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;

/**
 * Called with {@link EntityProjectile#shoot(Point, double, double)}
 * <p>
 * The entity is the shooter, or the projectile itself when it has none.
 */
public class EntityShootEvent implements EntityInstanceEvent, CancellableEvent {

    private final Entity entity;
    private final Entity projectile;
    private final Point to;
    private double power;
    private double spread;

    private boolean cancelled;

    public EntityShootEvent(Entity entity, Entity projectile, Point to, double power, double spread) {
        this.entity = entity;
        this.projectile = projectile;
        this.to = to;
        this.power = power;
        this.spread = spread;
    }

    /**
     * Gets the projectile.
     *
     * @return the projectile.
     */
    public Entity getProjectile() {
        return this.projectile;
    }

    /**
     * Gets the position projectile was shot to.
     *
     * @return the position projectile was shot to.
     */
    public Point getTo() {
        return this.to;
    }

    /**
     * Gets shot spread.
     *
     * @return shot spread.
     */
    public double getSpread() {
        return this.spread;
    }

    /**
     * Sets shot spread.
     *
     * @param spread shot spread.
     */
    public void setSpread(double spread) {
        this.spread = spread;
    }

    /**
     * Gets shot power.
     *
     * @return shot power.
     */
    public double getPower() {
        return this.power;
    }

    /**
     * Sets shot power.
     *
     * @param power shot power.
     */
    public void setPower(double power) {
        this.power = power;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
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
