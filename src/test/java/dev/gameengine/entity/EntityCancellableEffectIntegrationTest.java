package dev.gameengine.entity;

import dev.gameengine.coordinate.Vec;
import dev.gameengine.event.entity.EntityPotionAddEvent;
import dev.gameengine.potion.Potion;
import dev.gameengine.potion.PotionEffect;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@EnvTest
public class EntityCancellableEffectIntegrationTest {

    @Test
    public void cancelEffect(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        LivingEntity entity = new LivingEntity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Vec(0, 0, 0)).join();

        Potion potion = new Potion(PotionEffect.ABSORPTION, 0, Potion.INFINITE_DURATION);
        env.process().eventHandler().addListener(EntityPotionAddEvent.class, event -> event.setCancelled(true));
        entity.addEffect(potion);

        assertFalse(entity.hasEffect(PotionEffect.ABSORPTION));
    }

}
