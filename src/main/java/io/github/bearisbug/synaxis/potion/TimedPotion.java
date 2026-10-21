package io.github.bearisbug.synaxis.potion;

import io.github.bearisbug.synaxis.entity.Entity;

/**
 * An effect applied to an entity, see {@link Entity#addEffect(Potion)}.
 *
 * @param potion        the effect; one whose duration is {@link Potion#INFINITE_DURATION} never expires
 * @param startingTicks the {@link Entity#getAliveTicks()} of the entity when the effect was applied, not a world time
 */
public record TimedPotion(Potion potion, long startingTicks) {

    /**
     * Gets how long the effect still lasts.
     *
     * @param aliveTicks the current {@link Entity#getAliveTicks()} of the entity
     * @return the ticks left, 0 once the effect expired, {@link Potion#INFINITE_DURATION} for an effect that never
     * expires
     */
    public int remainingTicks(long aliveTicks) {
        final int duration = potion.duration();
        if (duration == Potion.INFINITE_DURATION) return Potion.INFINITE_DURATION;
        return (int) Math.max(0, startingTicks + duration - aliveTicks);
    }
}
