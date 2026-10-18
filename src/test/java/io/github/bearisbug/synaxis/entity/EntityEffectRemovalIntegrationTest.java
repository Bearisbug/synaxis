package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.entity.EntityPotionRemoveEvent;
import io.github.bearisbug.synaxis.potion.Potion;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener of {@link EntityPotionRemoveEvent} giving the next effect, such as slowness once speed is over,
 * changes the effects of the entity while one is being removed.
 */
@EnvTest
public class EntityEffectRemovalIntegrationTest {

    @Test
    public void anEffectAddedWhenAnotherExpiresIsKept(Env env) {
        var instance = env.createFlatInstance();
        var entity = new LivingEntity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        var speedRemovals = new AtomicInteger();
        entity.eventNode().addListener(EntityPotionRemoveEvent.class, event -> {
            if (event.getPotion().effect() != PotionEffect.SPEED) return;
            speedRemovals.incrementAndGet();
            entity.addEffect(new Potion(PotionEffect.SLOWNESS, 0, Potion.INFINITE_DURATION));
        });

        entity.addEffect(new Potion(PotionEffect.SPEED, 0, 2));
        for (int i = 0; i < 10; i++) env.tick();

        assertEquals(1, speedRemovals.get(), "the expired effect was removed again on every tick");
        assertFalse(entity.hasEffect(PotionEffect.SPEED));
        assertTrue(entity.hasEffect(PotionEffect.SLOWNESS));
    }

    @Test
    public void anEffectAddedWhileTheEffectsAreClearedIsKept(Env env) {
        var instance = env.createFlatInstance();
        var entity = new LivingEntity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        entity.eventNode().addListener(EntityPotionRemoveEvent.class, event -> {
            if (event.getPotion().effect() == PotionEffect.SPEED)
                entity.addEffect(new Potion(PotionEffect.SLOWNESS, 0, Potion.INFINITE_DURATION));
        });
        entity.addEffect(new Potion(PotionEffect.SPEED, 0, Potion.INFINITE_DURATION));

        entity.clearEffects();

        assertFalse(entity.hasEffect(PotionEffect.SPEED));
        assertTrue(entity.hasEffect(PotionEffect.SLOWNESS), "the client was told about an effect cleared right after");
    }
}
