package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.collision.BoundingBox;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.metadata.projectile.ProjectileMeta;
import io.github.bearisbug.synaxis.event.entity.EntityShootEvent;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileCollideWithBlockEvent;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileCollideWithEntityEvent;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileUncollideEvent;
import io.github.bearisbug.synaxis.instance.EntityTracker;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.thread.Acquirable;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Class that allows to instantiate entities with projectile-like physics handling.
 */
public class EntityProjectile extends Entity {
    /**
     * The shortest step between the points where a moving projectile is checked for hits, half the width of
     * its box otherwise: a box without width would never move from its first point.
     */
    private static final double MIN_STEP = 0.125;

    private final Entity shooter;
    private boolean wasStuck;

    @SuppressWarnings("this-escape") // deliberate self registration during construction
    public EntityProjectile(@Nullable Entity shooter, EntityType entityType) {
        super(entityType);
        this.shooter = shooter;
        this.hasPhysics = false;
        if (getEntityMeta() instanceof ProjectileMeta meta) meta.setShooter(shooter);
    }

    @Nullable
    public Entity getShooter() {
        return this.shooter;
    }

    /**
     * Shoots the projectile at a point, from the eyes of its shooter or, without one, from its own position.
     * <p>
     * Calls {@link EntityShootEvent} first: the projectile is removed if it is cancelled, and is shot with the
     * power and spread the listeners left.
     *
     * @param to     the point to shoot at
     * @param power  the speed in blocks per tick, times the ticks per second for the velocity in blocks per second
     * @param spread the inaccuracy: times 0.0075, the standard deviation of the gaussian noise added to each axis
     *               of the unit direction
     * @throws IllegalArgumentException if the power or spread is not finite, or the projectile has no direction to
     *                                  go, such as a point at its starting position
     */
    public void shoot(Point to, double power, double spread) {
        final Entity source = this.shooter != null ? this.shooter : this;
        final EntityShootEvent shootEvent = new EntityShootEvent(source, this, to, power, spread);
        callEvent(shootEvent);
        if (shootEvent.isCancelled()) {
            remove();
            return;
        }
        final Pos from = this.shooter != null
                ? this.shooter.getPosition().add(0D, this.shooter.getEyeHeight(), 0D)
                : getPosition();
        shoot(from, to, shootEvent.getPower(), shootEvent.getSpread());
    }

    private void shoot(Point from, Point to, double power, double spread) {
        Check.argCondition(!Double.isFinite(power) || !Double.isFinite(spread),
                "Power and spread must be finite: {0}, {1}", power, spread);
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        if (!hasNoGravity()) {
            final double xzLength = Math.sqrt(dx * dx + dz * dz);
            dy += xzLength * 0.20000000298023224D;
        }

        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Check.argCondition(length == 0, "No direction to shoot from {0} to {1}", from, to);
        dx /= length;
        dy /= length;
        dz /= length;
        Random random = ThreadLocalRandom.current();
        spread *= 0.007499999832361937D;
        dx += random.nextGaussian() * spread;
        dy += random.nextGaussian() * spread;
        dz += random.nextGaussian() * spread;

        final double mul = 20 * power;
        this.velocity = new Vec(dx * mul, dy * mul, dz * mul);
        setView(
                (float) Math.toDegrees(Math.atan2(dx, dz)),
                (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))
        );
    }

    @Override
    public void tick(long time) {
        final Pos posBefore = getPosition();
        super.tick(time);
        if (isRemoved()) return;
        // Outside the simulation distance only the scheduler runs (docs/DESIGN-M3.md ADR-M3-017)
        if (outsideSimulation()) return;

        final Pos posNow = getPosition();
        boolean isStuck = isStuck(posBefore, posNow);
        if (isRemoved()) return;
        if (isStuck) {
            // The movement clears it every tick: set it again, between ticks isOnGround() tells the projectile
            // is stuck (docs/DESIGN-M3.md ADR-M3-017)
            onGround = true;
            if (wasStuck) return;
            wasStuck = true;
            this.velocity = Vec.ZERO;
            sendPacketToViewersAndSelf(getVelocityPacket());
            setNoGravity(true);
        } else {
            if (!wasStuck) return;
            wasStuck = false;
            onGround = false;
            setNoGravity(false);
            callEvent(new ProjectileUncollideEvent(this));
        }
    }

    /**
     * Moves through the points between the two positions, checking each for a block to get stuck in and for
     * an entity to hit, and calls the collision events.
     * <p>
     * Hitting an entity only calls {@link ProjectileCollideWithEntityEvent}: the projectile neither stops nor is
     * removed, it keeps flying. A target whose hit was cancelled is skipped at the next points of this tick.
     *
     * @param pos    position right before current tick.
     * @param posNow position after current tick.
     * @return if the projectile is stuck in a block
     */
    @SuppressWarnings("ConstantConditions")
    private boolean isStuck(Pos pos, Pos posNow) {
        final Instance instance = getInstance();
        if (pos.samePoint(posNow)) {
            return instance.getBlock(pos).solid();
        }

        final BoundingBox bb = getBoundingBox();

        /*
          What we're about to do is to discretely jump from a previous position to the new one.
          For each point we will be checking blocks and entities we're in.
         */
        final double part = Math.max(bb.width() / 2, MIN_STEP);
        final Vec dir = posNow.sub(pos).asVec();
        final int parts = (int) Math.ceil(dir.length() / part);
        final Pos direction = dir.normalize().mul(part).asPos();
        final long aliveTicks = getAliveTicks();
        Block block = null;
        Point blockPos = null;
        // The entities of the chunk of the point, an entity whose box reaches into it from another chunk is not hit
        Collection<Entity> candidates = null;
        Point candidatesAt = null;
        List<Entity> ignored = null;
        for (int i = 0; i < parts; ++i) {
            // If we're at last part, we can't just add another direction-vector, because we can exceed the end point.
            pos = (i == parts - 1) ? posNow : pos.add(direction);
            if (block == null || !pos.sameBlock(blockPos)) {
                block = instance.getBlock(pos);
                blockPos = pos;
            }
            if (block.solid()) {
                final ProjectileCollideWithBlockEvent event = new ProjectileCollideWithBlockEvent(this, pos, block);
                callEvent(event);
                if (isRemoved()) return true;
                if (!event.isCancelled()) {
                    teleport(pos).join();
                    return true;
                }
            }
            if (candidates == null || !pos.sameChunk(candidatesAt)) {
                candidates = instance.getEntityTracker().chunkEntities(pos, EntityTracker.Target.ENTITIES);
                candidatesAt = pos;
            }
            LivingEntity target = null;
            for (Entity entity : candidates) {
                /*
                  We won't check collisions with a shooter for first ticks of arrow's life, because it spawns in him
                  and will immediately deal damage.
                 */
                if (entity instanceof LivingEntity living && !(aliveTicks < 3 && entity == shooter)
                        && (ignored == null || !ignored.contains(entity)) && bb.intersectEntity(pos, entity)) {
                    target = living;
                    break;
                }
            }
            if (target != null) {
                final ProjectileCollideWithEntityEvent event = new ProjectileCollideWithEntityEvent(this, pos, target);
                callEvent(event);
                if (!event.isCancelled()) return false;
                if (ignored == null) ignored = new ArrayList<>();
                ignored.add(target);
            }
        }
        return false;
    }

    @ApiStatus.Experimental
    @SuppressWarnings("unchecked")
    @Override
    public Acquirable<? extends EntityProjectile> acquirable() {
        return (Acquirable<? extends EntityProjectile>) super.acquirable();
    }
}
