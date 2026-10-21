package io.github.bearisbug.synaxis.event.entity;

import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Called with {@link LivingEntity#damage(Damage)}.
 * <p>
 * The amount is changed with {@code getDamage().setAmount(float)}, which refuses NaN; a negative
 * amount heals, or adds to the additional hearts of a player who has some. Cancelling it applies no
 * damage and plays neither the animation nor the sound.
 */
public class EntityDamageEvent implements EntityInstanceEvent, CancellableEvent {

    private final LivingEntity entity;
    private final Damage damage;
    private SoundEvent sound;
    private boolean animation = true;

    private boolean cancelled;

    public EntityDamageEvent(LivingEntity entity, Damage damage, @Nullable SoundEvent sound) {
        this.entity = entity;
        this.damage = damage;
        this.sound = sound;
    }

    @Override
    public LivingEntity getEntity() {
        return entity;
    }

    /**
     * Gets the damage type.
     *
     * @return the damage type
     */
    public Damage getDamage() {
        return damage;
    }

    /**
     * Gets the damage sound.
     *
     * @return the damage sound
     */
    @Nullable
    public SoundEvent getSound() {
        return sound;
    }

    /**
     * Changes the damage sound.
     *
     * @param sound the new damage sound
     */
    public void setSound(@Nullable SoundEvent sound) {
        this.sound = sound;
    }

    /**
     * Gets whether the damage animation should be played.
     *
     * @return true if the animation should be played
     */
    public boolean shouldAnimate() {
        return animation;
    }

    /**
     * Sets whether the damage animation should be played.
     *
     * @param animation whether the animation should be played or not
     */
    public void setAnimation(boolean animation) {
        this.animation = animation;
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
