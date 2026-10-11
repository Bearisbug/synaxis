package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.event.entity.EntityDamageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MELEE} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}): a player's attack on a
 * living entity hurts it by the attacker's attack damage as a {@code minecraft:player_attack}, knocks it back along the
 * attacker's look, and lands at most once in 10 ticks of the target; a cancelled damage event does neither.
 */
@EnvTest
class DefaultPlayMeleeIntegrationTest {

    /**
     * Looking along +x at the target 3 blocks east.
     */
    private static final Pos EAST = new Pos(0.5, 40, 0.5, -90, 0);

    private static final Pos TARGET = new Pos(3.5, 40, 0.5, 90, 0);

    @Test
    void aHitTakesTheAttackDamageAndKnocksBackOnceInTenTicks(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.MELEE), null);
        final Player player = env.createPlayer(instance, EAST);
        // An iron pickaxe in the main hand: attack damage 1 + 3
        player.getInventory().setItemStack(0, ItemStack.of(Material.IRON_PICKAXE));
        final LivingEntity target = target(instance);
        final Collector<EntityDamageEvent> damages = env.trackEvent(EntityDamageEvent.class, EventFilter.ENTITY, target);

        attack(env, player, target);
        assertEquals(16f, target.getHealth(), "after the hit of tick 0");
        assertTrue(target.getVelocity().x() > 0, "knocked back along +x, the velocity is " + target.getVelocity());
        for (int tick = 0; tick < 5; tick++) env.tick();
        target.acquirable().sync(owned -> owned.setVelocity(owned.getVelocity().withX(0)));
        attack(env, player, target);
        assertEquals(16f, target.getHealth(), "after the hit of tick 5, within the cooldown");
        assertEquals(0, target.getVelocity().x(), "knocked back within the cooldown");
        for (int tick = 0; tick < 7; tick++) env.tick();
        attack(env, player, target);
        assertEquals(12f, target.getHealth(), "after the hit of tick 12");

        damages.assertCount(2);
        for (EntityDamageEvent event : damages.collect()) {
            assertEquals(DamageType.PLAYER_ATTACK, event.getDamage().getType());
            assertSame(player, event.getDamage().getAttacker());
            assertSame(player, event.getDamage().getSource());
            assertEquals(4f, event.getDamage().getAmount());
        }
    }

    /**
     * A listener where a global scope plugin listener at {@code HIGHEST} runs cancels the player attack: no health is
     * taken and no knockback given.
     */
    @Test
    void aCancelledPlayerAttackNeitherHurtsNorKnocksBack(Env env) {
        final Instance instance = env.createFlatInstance();
        final EventNode<Event> hostNode = PlayFixture.hostNode(env);
        PlayFixture.attach(env, hostNode, EnumSet.of(Feature.MELEE), null);
        PlayFixture.pluginTier(hostNode, 2).addListener(EntityDamageEvent.class, event -> {
            if (event.getDamage().getType().equals(DamageType.PLAYER_ATTACK)) event.setCancelled(true);
        });
        final Player player = env.createPlayer(instance, EAST);
        final LivingEntity target = target(instance);

        attack(env, player, target);

        assertEquals(20f, target.getHealth());
        assertEquals(0, target.getVelocity().x(), "the velocity is " + target.getVelocity());
    }

    /**
     * Two players attack the target in the same tick, each attack read off the attacker's own packet queue by
     * {@code UseEntityListener}: the first to reach the target lands and the other falls in the cooldown, which is the
     * target's whoever attacks ({@code docs/DESIGN-PROD.md} §16). With 4 tick threads on the grid
     * ({@code -Dsynaxis.thread-provider=grid}, cells of 16 by 16 chunks) the target and the two attackers stand in three
     * cells around the origin, so the two attacks run on two tick threads and meet in {@code Acquirable#sync} on the
     * target's.
     */
    @Test
    void twoAttackersInOneTickLandOneHit(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.MELEE), null);
        final LivingEntity target = new LivingEntity(EntityType.ZOMBIE);
        target.setInstance(instance, new Pos(0.5, 40, 0.5)).join();
        // In cells (-1, 0) and (0, -1), each looking at the target in cell (0, 0)
        final Player west = env.createPlayer(instance, new Pos(-1.5, 40, 0.5, -90, 0));
        final Player north = env.createPlayer(instance, new Pos(0.5, 40, -1.5, 0, 0));
        west.getInventory().setItemStack(0, ItemStack.of(Material.IRON_PICKAXE));
        north.getInventory().setItemStack(0, ItemStack.of(Material.IRON_PICKAXE));
        // Assigns the three to their tick threads, which an Acquirable#sync from another tick thread needs
        env.tick();
        final ServerConfig config = env.process().config();
        if (config.threadProvider() == ServerConfig.ThreadProviderKind.GRID && config.dispatcherThreads() >= 4)
            assertEquals(3, Stream.of(target, west, north).map(entity -> entity.acquirable().assignedThread())
                    .distinct().count(), "the target and the two attackers on three tick threads");
        final Collector<EntityDamageEvent> damages = env.trackEvent(EntityDamageEvent.class, EventFilter.ENTITY, target);

        west.addPacketToQueue(new ClientAttackPacket(target.getEntityId()));
        north.addPacketToQueue(new ClientAttackPacket(target.getEntityId()));
        env.tick();

        damages.assertSingle();
        assertEquals(16f, target.getHealth(), "after the two hits of one tick");
    }

    private static LivingEntity target(Instance instance) {
        final LivingEntity target = new LivingEntity(EntityType.ZOMBIE);
        target.setInstance(instance, TARGET).join();
        return target;
    }

    /**
     * What the engine dispatches for a client's attack on an entity it sees ({@code UseEntityListener}).
     */
    private static void attack(Env env, Player player, LivingEntity target) {
        env.process().eventHandler().call(new EntityAttackEvent(player, target));
    }
}
