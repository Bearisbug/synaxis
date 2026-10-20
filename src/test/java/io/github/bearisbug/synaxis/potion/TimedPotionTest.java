package io.github.bearisbug.synaxis.potion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimedPotionTest {

    @Test
    public void theTicksLeftCountFromTheTickTheEffectWasApplied() {
        final TimedPotion effect = new TimedPotion(new Potion(PotionEffect.SPEED, 0, 100), 40);
        assertEquals(100, effect.remainingTicks(40));
        assertEquals(30, effect.remainingTicks(110));
        assertEquals(1, effect.remainingTicks(139));
    }

    @Test
    public void anExpiredEffectHasNoTickLeft() {
        final TimedPotion effect = new TimedPotion(new Potion(PotionEffect.SPEED, 0, 100), 40);
        assertEquals(0, effect.remainingTicks(140));
        assertEquals(0, effect.remainingTicks(1_000));
    }

    @Test
    public void anInfiniteEffectNeverRunsOut() {
        final TimedPotion effect = new TimedPotion(new Potion(PotionEffect.SPEED, 0, Potion.INFINITE_DURATION), 40);
        assertEquals(Potion.INFINITE_DURATION, effect.remainingTicks(40));
        assertEquals(Potion.INFINITE_DURATION, effect.remainingTicks(Long.MAX_VALUE / 2));
    }
}
