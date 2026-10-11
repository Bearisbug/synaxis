package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.RESPAWN;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.assertSavedStateApplied;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.leave;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.online;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.play;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.saved;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.seed;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.slots;
import static io.github.bearisbug.synaxis.app.store.PlayerStateFixture.stored;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code synaxis:player} on the store in memory ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-047},
 * {@code API-PROD-032}, §12.5, §16): applied at the first spawn under each mode's position rule, captured at the
 * snapshots only once applied, written only when it changed.
 */
@EnvTest
class PlayerStateSectionIntegrationTest {

    private static final Pos LEFT_AT = new Pos(8.5, 40, -3.5, 45, 10);
    private static final ItemStack PICKAXE = ItemStack.builder(Material.IRON_PICKAXE).set(DataComponents.DAMAGE, 3).build();
    private static final Map<Integer, ItemStack> INVENTORY = Map.of(0, ItemStack.of(Material.STONE, 63), 1, PICKAXE,
            9, ItemStack.of(Material.DIRT, 9));

    @Test
    void aPlayerComesBackWhereItLeftWithItsInventoryHeldSlotAndHealth(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Returner");
        assertTrue(memory.section().isFresh(first), "a character nothing was captured for");
        play(first, LEFT_AT, 2, 13, INVENTORY);

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Returner");
        env.tick();

        assertEquals(LEFT_AT, again.getPosition(), "where it left, facing the same way");
        assertEquals(2, again.getHeldSlot());
        assertEquals(13f, again.getHealth());
        assertEquals(slots(INVENTORY), slots(again), "the 46 slots");
        assertEquals(1, memory.store().versionOf(again), "written once, by the logout");
        assertFalse(memory.section().isFresh(again), "a character captured once");
    }

    /**
     * A snapshot that finds nothing changed marks nothing dirty: no write, no version. A player that came back with
     * its saved state captures that same state, items with components included.
     */
    @Test
    void aSnapshotThatFindsNothingChangedWritesNothing(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), StoreFixture.FAST);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, memory.store(), instance, account, "Idler");
        await(env, () -> memory.store().versionOf(player) == 1 && !memory.store().isWriting(player),
                "the first autosave writes the state the player spawned in");

        idle(env, memory.store(), player);
        assertEquals(1, memory.store().versionOf(player), "five autosaves of a player that did not move");

        play(player, LEFT_AT, 2, 13, INVENTORY);
        await(env, () -> memory.store().versionOf(player) == 2 && !memory.store().isWriting(player),
                "the next autosave writes the change");
        leave(env, memory.store(), player);
        final Player again = online(env, memory.store(), instance, account, "Idler");
        assertEquals(3, memory.store().versionOf(again), "the logout wrote once");

        idle(env, memory.store(), again);
        assertEquals(3, memory.store().versionOf(again), "five autosaves of the state it came back with");
    }

    /**
     * Between the admission and the first spawn the player carries the engine's starting state; its logout leaves the
     * stored section as it is, and a new character stays new.
     */
    @Test
    void aLogoutBeforeTheFirstSpawnLeavesTheSavedStateAlone(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID returning = UUID.randomUUID();
        final UUID newcomer = UUID.randomUUID();
        seed(memory.backend(), returning, saved("{\"kind\": \"core\"}", LEFT_AT));
        final String before = stored(memory.backend(), returning);

        for (UUID account : new UUID[]{returning, newcomer}) {
            final Player player = connect(env.process(), account, "Hasty");
            assertTrue(memory.store().load(player));
            assertTrue(memory.store().admitted(player));
            // Still configuring: the disconnect saves right here
            player.getPlayerConnection().disconnect();
            await(env, () -> memory.store().versionOf(player) == -1, "the logout save");
        }

        assertEquals(before, stored(memory.backend(), returning), "the saved state of the returning character");
        assertNull(stored(memory.backend(), newcomer), "a section for the new character");
        final Player again = online(env, memory.store(), instance, returning, "Hasty");
        env.tick();
        assertEquals(LEFT_AT, again.getPosition());
        assertSavedStateApplied(again, "the returning character");
        final Player newcomerAgain = online(env, memory.store(), instance, newcomer, "Hasty2");
        assertTrue(memory.section().isFresh(newcomerAgain), "the new character is still new");
    }

    /**
     * The shutdown save disconnects a player that is in no instance yet, here one still configuring; its snapshot
     * leaves the stored section alone.
     */
    @Test
    void aShutdownBeforeTheFirstSpawnLeavesTheSavedStateAlone(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", LEFT_AT));
        final String before = stored(memory.backend(), account);
        final Player player = connect(env.process(), account, "Late");
        assertTrue(memory.store().load(player));
        assertTrue(memory.store().admitted(player));

        final CompletableFuture<?> saved = memory.store().saveAll(Duration.ofSeconds(5), true);
        await(env, saved::isDone, "the shutdown save");

        assertEquals(List.of(DisconnectReasons.SERVER_STOPPING.key()), kicks(player), "disconnected outside the world");
        assertEquals(before, stored(memory.backend(), account));
    }

    /** Health 0 is a player that left on the death screen: it comes back as a respawn, at full health. */
    @Test
    void aPlayerThatLeftOnTheDeathScreenComesBackAsARespawn(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Fallen");
        play(first, LEFT_AT, 0, 20, INVENTORY);
        first.acquirable().sync(_ -> first.kill());
        assertTrue(first.isDead());

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Fallen");
        env.tick();

        assertEquals(20f, again.getHealth());
        assertFalse(again.isDead());
        assertEquals(RESPAWN, again.getPosition(), "at the respawn point, not where it died");
        assertEquals(slots(INVENTORY), slots(again), "a death drops nothing");
    }

    /** A saved health above the maximum is applied as the maximum. */
    @Test
    void aSavedHealthAboveTheMaximumIsTheMaximum(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", LEFT_AT).replace("\"health\": 9.0", "\"health\": 35.0"));

        final Player player = online(env, memory.store(), env.createFlatInstance(), account, "Hale");

        assertEquals(20f, player.getHealth());
    }

    /** Under a gameplay plugin its spawn decides where the player is; the rest of the saved state is applied. */
    @Test
    void underTheRtsRuleThePluginSpawnDecidesThePosition(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Rts(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Commander");
        play(first, LEFT_AT, 2, 13, INVENTORY);

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Commander");
        env.tick();

        assertEquals(RESPAWN, again.getPosition(), "the spawn the plugin set");
        assertEquals(2, again.getHeldSlot());
        assertEquals(13f, again.getHealth());
        assertEquals(slots(INVENTORY), slots(again));
        final String written = stored(memory.backend(), account);
        assertNotNull(written);
        assertTrue(written.contains("\"kind\":\"rts\""), written);
    }

    /** The core rule only takes a position saved in the core world: a map's coordinates mean nothing there. */
    @Test
    void theCoreRuleTakesOnlyAPositionSavedInTheCoreWorld(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"map\", \"map_id\": \"demo\", \"build_id\": \"b1\"}", LEFT_AT));

        final Player player = online(env, memory.store(), env.createFlatInstance(), account, "Visitor");
        env.tick();

        assertEquals(RESPAWN, player.getPosition());
        assertSavedStateApplied(player, "a state saved on a map");
    }

    /**
     * A map shard puts a player back only for a position saved on the same map release inside its bounds, and not
     * when it arrives with a transfer ticket: then it stays at the arrival point its respawn point was set to.
     */
    @Test
    void aMapShardTakesTheSavedPositionOnlyWhenAllThreeHold(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env,
                new PositionRule.MapShard("demo", "b1", new SynxManifest.Bounds(0, 0, 3, 3)), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final Pos inside = new Pos(40.5, 40, 40.5, 90, 0);
        final Map<String, String> cases = Map.of(
                "the same release, inside", saved("{\"kind\": \"map\", \"map_id\": \"demo\", \"build_id\": \"b1\"}", inside),
                "another map", saved("{\"kind\": \"map\", \"map_id\": \"other\", \"build_id\": \"b1\"}", inside),
                "another build", saved("{\"kind\": \"map\", \"map_id\": \"demo\", \"build_id\": \"b2\"}", inside),
                "outside the bounds", saved("{\"kind\": \"map\", \"map_id\": \"demo\", \"build_id\": \"b1\"}",
                        new Pos(64.5, 40, 40.5)),
                "the core world", saved("{\"kind\": \"core\"}", inside));

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            final UUID account = UUID.randomUUID();
            seed(memory.backend(), account, entry.getValue());
            final Player player = online(env, memory.store(), instance, account, "Traveller");
            env.tick();
            assertEquals(entry.getKey().equals("the same release, inside") ? inside : RESPAWN, player.getPosition(),
                    entry.getKey());
            assertSavedStateApplied(player, entry.getKey());
        }

        final UUID arriving = UUID.randomUUID();
        seed(memory.backend(), arriving, cases.get("the same release, inside"));
        final Player player = connect(env.process(), arriving, "Arrival");
        assertTrue(memory.store().load(player));
        memory.section().claimed(player);
        assertTrue(memory.store().admitted(player));
        StoreFixture.spawn(env.process(), player, instance);
        env.tick();
        assertEquals(RESPAWN, player.getPosition(), "a transferred player stays at its arrival point");
        assertSavedStateApplied(player, "a transferred player");
    }

    /**
     * {@code synaxis:} is the host's namespace: the store takes no other host section and no section twice, a plugin
     * reaches none of them, and a section takes one position rule.
     */
    @Test
    void theHostSectionIsTheHostsAlone(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Player player = online(env, memory.store(), env.createFlatInstance(), UUID.randomUUID(), "Owner");

        assertThrows(IllegalArgumentException.class,
                () -> memory.store().registerHostSection(StoreFixture.PURSE, _ -> null), "a section outside synaxis:");
        assertThrows(IllegalStateException.class,
                () -> memory.store().registerHostSection(new PlayerStateSection(env.process().registries()), _ -> null),
                "synaxis:player twice");
        assertThrows(IllegalStateException.class, () -> memory.section().useRule(new PositionRule.Rts()), "a second rule");
        final PluginPlayerData plugin = memory.store().forPlugin("synaxis");
        assertThrows(IllegalStateException.class, () -> plugin.get(player, memory.section()), "a plugin reading it");
        assertThrows(IllegalStateException.class, () -> plugin.markDirty(player, memory.section()), "a plugin marking it");
    }

    /** Twenty ticks, five autosave periods of {@link StoreFixture#FAST}, and whatever they wrote written. */
    private static void idle(Env env, PlayerStore store, Player player) {
        for (int tick = 0; tick < 20; tick++) env.tick();
        await(env, () -> !store.isWriting(player), "the autosaves are over");
    }
}
