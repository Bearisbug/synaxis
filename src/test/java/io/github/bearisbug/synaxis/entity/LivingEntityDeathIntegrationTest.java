package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.entity.EntityDeathEvent;
import io.github.bearisbug.synaxis.network.packet.server.play.DeathCombatEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.RespawnPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
    public void healingInDeathEventLeavesTheDyingPose(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        zombie.eventNode().addListener(EntityDeathEvent.class, event -> ((LivingEntity) event.getEntity()).heal());

        zombie.kill();

        assertEquals(EntityPose.STANDING, zombie.getPose(), "a revived entity must not stay in the dying pose");
    }

    @Test
    public void aCreatureHealedInItsDeathEventIsNotRemoved(Env env) {
        var instance = env.createFlatInstance();
        var creature = new EntityCreature(EntityType.ZOMBIE);
        creature.setRemovalAnimationDelay(0); // Removed right in kill() otherwise
        creature.setInstance(instance, new Pos(0, 42, 0)).join();
        creature.eventNode().addListener(EntityDeathEvent.class, event -> ((LivingEntity) event.getEntity()).heal());

        creature.damage(new Damage(DamageType.GENERIC, null, null, null, 100f));

        assertFalse(creature.isDead());
        assertFalse(creature.isRemoved(), "the creature was removed although its death was cancelled");
    }

    @Test
    public void aPlayerHealedInItsDeathEventGetsNoDeathScreenNorDeathMessage(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 42, 0));
        var deathScreens = connection.trackIncoming(DeathCombatEventPacket.class);
        var chat = connection.trackIncoming(SystemChatPacket.class);
        player.eventNode().addListener(EntityDeathEvent.class, event -> ((LivingEntity) event.getEntity()).heal());

        player.damage(new Damage(DamageType.GENERIC, null, null, null, 100f));

        assertFalse(player.isDead());
        deathScreens.assertEmpty();
        chat.assertEmpty();
    }

    /** The death screen stays on the client after the player is healed, its respawn button must still work. */
    @Test
    public void aPlayerHealedOnItsDeathScreenStillRespawns(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 42, 0));
        var deathScreens = connection.trackIncoming(DeathCombatEventPacket.class);
        var respawns = connection.trackIncoming(RespawnPacket.class);

        player.kill();
        deathScreens.assertSingle();
        player.heal();
        player.respawn();

        respawns.assertSingle();
        var again = connection.trackIncoming(RespawnPacket.class); // A collector stops once read
        player.respawn(); // Respawned, the client is off its death screen
        again.assertEmpty();
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
