package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.entity.EntityDeathEvent;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class LivingEntityDeathIntegrationTest {

    @Test
    public void healingInDeathEventDoesNotBrickTheEntity(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        zombie.eventNode().addListener(EntityDeathEvent.class, event -> ((LivingEntity) event.getEntity()).heal());

        zombie.damage(new Damage(DamageType.GENERIC, null, null, null, 100f));

        assertFalse(zombie.isDead(), "healing in EntityDeathEvent must clear isDead");
        assertTrue(zombie.damage(new Damage(DamageType.GENERIC, null, null, null, 1f)),
                "entity became permanently invulnerable");
    }

    @Test
    public void killIsIdempotent(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        var deaths = env.trackEvent(EntityDeathEvent.class, EventFilter.ENTITY, zombie);

        zombie.kill();
        zombie.kill();
        deaths.assertCount(1);
    }

    @Test
    public void healthIsClampedToZero(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();

        zombie.kill();
        zombie.setHealth(-50f); // Already dead, so nothing re-clamps the value
        assertEquals(0f, zombie.getHealth(), "health must never go below zero");
    }
}
