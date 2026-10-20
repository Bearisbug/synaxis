package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.thread.Acquirable;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/**
 * A living entity that can have a target to attack.
 * <p>
 * Created without a {@link io.github.bearisbug.synaxis.ServerProcess}, it dispatches no event until its first
 * {@link #setInstance(io.github.bearisbug.synaxis.instance.Instance)}, see {@link Entity#Entity(EntityType, UUID)}.
 */
public class EntityCreature extends LivingEntity {

    private int removalAnimationDelay = 1000;

    private @Nullable Entity target;

    /**
     * Constructor which allows to specify an UUID. Only use if you know what you are doing!
     */
    public EntityCreature(EntityType entityType, UUID uuid) {
        super(entityType, uuid);
    }

    public EntityCreature(EntityType entityType) {
        this(entityType, UUID.randomUUID());
    }

    /**
     * Kills the creature and removes it after {@link #getRemovalAnimationDelay()}. Healing it back in
     * {@link io.github.bearisbug.synaxis.event.entity.EntityDeathEvent} keeps it; healing it later does
     * not cancel the removal already scheduled.
     */
    @Override
    public void kill() {
        super.kill();
        // A listener of the death event healed it back
        if (!isDead()) return;

        if (removalAnimationDelay > 0) {
            // Needed for proper death animation (wait for it to finish before destroying the entity)
            scheduleRemove(Duration.of(removalAnimationDelay, TimeUnit.MILLISECOND));
        } else {
            // Instant removal without animation playback
            remove();
        }
    }

    /**
     * Gets the kill animation delay before vanishing the entity.
     *
     * @return the removal animation delay in milliseconds, 0 if not any
     */
    public int getRemovalAnimationDelay() {
        return removalAnimationDelay;
    }

    /**
     * Changes the removal animation delay of the entity.
     * <p>
     * Testing shows that 1000 is the minimum value to display the death particles.
     *
     * @param removalAnimationDelay the new removal animation delay in milliseconds, 0 to remove it
     */
    public void setRemovalAnimationDelay(int removalAnimationDelay) {
        this.removalAnimationDelay = removalAnimationDelay;
    }

    /**
     * Gets the entity target.
     *
     * @return the entity target, can be null if not any
     */
    @Nullable
    public Entity getTarget() {
        final Entity target = this.target;
        // Nothing clears the target when it is removed, holding on to it would keep the whole
        // entity (a Player and its connection for instance) reachable for this creature's lifetime
        if (target != null && target.isRemoved()) {
            this.target = null;
            return null;
        }
        return target;
    }

    /**
     * Changes the entity target.
     *
     * @param target the new entity target, null to remove
     */
    public void setTarget(@Nullable Entity target) {
        this.target = target;
    }

    /**
     * Calls a {@link EntityAttackEvent} with this entity as the source and {@code target} as the target.
     *
     * @param target    the entity target
     * @param swingHand true to swing the entity main hand, false otherwise
     */
    public void attack(Entity target, boolean swingHand) {
        if (swingHand)
            swingMainHand();
        EntityAttackEvent attackEvent = new EntityAttackEvent(this, target);
        callEvent(attackEvent);
    }

    /**
     * Calls a {@link EntityAttackEvent} with this entity as the source and {@code target} as the target.
     * <p>
     * This does not trigger the hand animation.
     *
     * @param target the entity target
     */
    public void attack(Entity target) {
        attack(target, false);
    }

    @SuppressWarnings("unchecked")
    @ApiStatus.Experimental
    @Override
    public Acquirable<? extends EntityCreature> acquirable() {
        return (Acquirable<? extends EntityCreature>) super.acquirable();
    }
}
