package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

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

    @Test
    void aPlayerComesBackWhereItLeftWithItsInventoryHeldSlotAndHealth(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Returner");
        assertTrue(memory.section().isFresh(first), "a character nothing was captured for");
        play(first, LEFT_AT, 2, 13, inventory());

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Returner");
        env.tick();

        assertEquals(LEFT_AT, again.getPosition(), "where it left, facing the same way");
        assertEquals(2, again.getHeldSlot());
        assertEquals(13f, again.getHealth());
        assertEquals(slots(inventory()), slots(again), "the 46 slots");
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

        play(player, LEFT_AT, 2, 13, inventory());
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
        play(first, LEFT_AT, 0, 20, inventory());
        first.acquirable().sync(_ -> first.kill());
        assertTrue(first.isDead());

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Fallen");
        env.tick();

        assertEquals(20f, again.getHealth());
        assertFalse(again.isDead());
        assertEquals(RESPAWN, again.getPosition(), "at the respawn point, not where it died");
        assertEquals(slots(inventory()), slots(again), "a death drops nothing");
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
    void underTheGameplayRuleThePluginSpawnDecidesThePosition(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Gameplay(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Commander");
        play(first, LEFT_AT, 2, 13, inventory());

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Commander");
        env.tick();

        assertEquals(RESPAWN, again.getPosition(), "the spawn the plugin set");
        assertEquals(2, again.getHeldSlot());
        assertEquals(13f, again.getHealth());
        assertEquals(slots(inventory()), slots(again));
        final String written = stored(memory.backend(), account);
        assertNotNull(written);
        assertTrue(written.contains("\"kind\":\"gameplay\""), written);
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
     * The bounds are chunks with both ends included, and a position is in the chunk of its floored block: the last
     * block of the edge chunk 7 is inside the bounds 0..7, the first block past it is not, along x as along z.
     */
    @Test
    void theLastBlockOfTheEdgeChunkIsInsideTheBoundsAndTheNextOneIsNot(Env env) throws Exception {
        assertEdges(env, new SynxManifest.Bounds(0, 0, 7, 7), List.of(
                new Edge("x 127.9", new Pos(127.9, 40, 64.5), true),
                new Edge("x 128.0", new Pos(128.0, 40, 64.5), false),
                new Edge("z 127.9", new Pos(64.5, 40, 127.9), true),
                new Edge("z 128.0", new Pos(64.5, 40, 128.0), false)));
    }

    /**
     * Below zero the chunk is floored as well, not truncated towards zero: x -128.0 is in chunk -8, the first chunk of
     * the bounds -8..-1, and x -128.1 in chunk -9, past it.
     */
    @Test
    void aNegativePositionIsInTheChunkOfItsFlooredBlock(Env env) throws Exception {
        assertEdges(env, new SynxManifest.Bounds(-8, -8, -1, -1), List.of(
                new Edge("x -128.0", new Pos(-128.0, 40, -64.5), true),
                new Edge("x -128.1", new Pos(-128.1, 40, -64.5), false),
                new Edge("z -128.0", new Pos(-64.5, 40, -128.0), true),
                new Edge("z -128.1", new Pos(-64.5, 40, -128.1), false)));
    }

    private record Edge(String name, Pos saved, boolean inside) {
    }

    /** One character per edge, saved on the release of the rule: put back inside the bounds, left at the respawn point past them. */
    private static void assertEdges(Env env, SynxManifest.Bounds bounds, List<Edge> edges) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env,
                new PositionRule.MapShard("demo", "b1", bounds), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        for (Edge edge : edges) {
            final UUID account = UUID.randomUUID();
            seed(memory.backend(), account, saved("{\"kind\": \"map\", \"map_id\": \"demo\", \"build_id\": \"b1\"}",
                    edge.saved()));
            final Player player = online(env, memory.store(), instance, account, "Edge");
            await(env, () -> Boolean.TRUE.equals(player.getTag(PlayerStateSection.APPLIED)), edge.name() + ": applied");
            assertEquals(edge.inside() ? edge.saved() : RESPAWN, player.getPosition(), edge.name());
            assertSavedStateApplied(player, edge.name());
        }
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
        assertThrows(IllegalStateException.class, () -> memory.section().useRule(new PositionRule.Gameplay()), "a second rule");
        final PluginPlayerData plugin = memory.store().forPlugin("synaxis");
        assertThrows(IllegalStateException.class, () -> plugin.get(player, memory.section()), "a plugin reading it");
        assertThrows(IllegalStateException.class, () -> plugin.markDirty(player, memory.section()), "a plugin marking it");
    }

    /**
     * A saved position in a chunk still to load: the player stands at the respawn point until the chunk is loaded and
     * the teleport moves it, and until then its state is captured at the saved position. A player that leaves before,
     * having changed nothing, leaves the stored section as it is instead of writing the respawn point over it.
     */
    @Test
    void untilTheTeleportLandsTheStateIsCapturedAtTheSavedPosition(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Pos far = new Pos(1000.5, 40, 1000.5, 30, 0);
        final CountDownLatch chunkLoads = new CountDownLatch(1);
        final Instance instance = env.createFlatInstance(new GatedChunkLoader(far.chunkX(), far.chunkZ(), chunkLoads));
        final UUID leaving = UUID.randomUUID();
        final UUID waiting = UUID.randomUUID();
        seed(memory.backend(), leaving, saved("{\"kind\": \"core\"}", far));
        seed(memory.backend(), waiting, saved("{\"kind\": \"core\"}", far));
        final String before = stored(memory.backend(), leaving);

        final Player patient;
        try {
            final Player hasty = online(env, memory.store(), instance, leaving, "Hasty");
            env.tick();
            assertEquals(RESPAWN, hasty.getPosition(), "waiting for the chunk of the saved position");
            leave(env, memory.store(), hasty);
            assertEquals(before, stored(memory.backend(), leaving), "the saved state of a player that never got there");

            patient = online(env, memory.store(), instance, waiting, "Patient");
            env.tick();
            assertEquals(false, patient.getTag(PlayerStateSection.APPLIED), "the teleport is under way");
        } finally {
            chunkLoads.countDown();
        }
        await(env, () -> Boolean.TRUE.equals(patient.getTag(PlayerStateSection.APPLIED)), "applied once the chunk loaded");
        assertEquals(far, patient.getPosition(), "where the state is captured from");
        assertSavedStateApplied(patient, "a player that waited for its chunk");
    }

    /**
     * Back from a reconfiguration ({@code Player#startConfigurationPhase}) the engine spawns the player again with
     * {@code isFirstSpawn()} true, as it left its instance. The saved state is applied once: what the player did since
     * stays.
     */
    @Test
    void aSecondFirstSpawnAppliesNothing(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        // In the chunk of the respawn point, so the first teleport is done when the first spawn is
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", new Pos(5.5, 40, 6.5)));
        final Player player = online(env, memory.store(), instance, account, "Reconfigured");
        env.tick();
        assertSavedStateApplied(player, "the first spawn");
        play(player, LEFT_AT, 2, 13, inventory());

        // What Player#spawnPlayer dispatches when the player comes back from the configuration phase
        player.acquirable().sync(_ -> env.process().eventHandler().call(new PlayerSpawnEvent(player, instance, true)));

        assertEquals(slots(inventory()), slots(player), "the 46 slots");
        assertEquals(2, player.getHeldSlot(), "the held slot");
        assertEquals(13f, player.getHealth(), "the health");
        assertEquals(LEFT_AT, player.getPosition(), "the position");
    }

    /**
     * The same through the engine's own round trip, without a client: sent back to the configuration phase the player
     * leaves its instance, and on its way back the engine heals it and spawns it at its respawn point as a first spawn.
     * Nothing is applied from the store: the player keeps its inventory and held slot.
     */
    @Test
    void aRoundTripThroughTheConfigurationPhaseAppliesNothing(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", new Pos(5.5, 40, 6.5)));
        final Player player = online(env, memory.store(), instance, account, "Reconfigured");
        assertSavedStateApplied(player, "the first spawn");
        play(player, LEFT_AT, 2, 13, inventory());

        reconfigure(env, player);
        StoreFixture.spawn(env.process(), player, instance);

        assertEquals(RESPAWN, player.getPosition(), "spawned at the respawn point");
        assertEquals(slots(inventory()), slots(player), "the 46 slots");
        assertEquals(2, player.getHeldSlot(), "the held slot");
        assertEquals(20f, player.getHealth(), "healed by the engine on the way back");
    }

    /**
     * A round trip through the configuration phase while the first spawn's teleport to the saved position is under
     * way applies nothing a second time either: what the player did meanwhile stays, and the teleport still lands.
     */
    @Test
    void aRoundTripWhileTheTeleportIsUnderWayAppliesNothing(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Pos far = new Pos(1000.5, 40, 1000.5, 30, 0);
        final CountDownLatch chunkLoads = new CountDownLatch(1);
        final Instance instance = env.createFlatInstance(new GatedChunkLoader(far.chunkX(), far.chunkZ(), chunkLoads));
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", far));
        final List<ItemStack> changed = slots(Map.of(0, ItemStack.of(Material.STONE, 63), 4, ItemStack.of(Material.DIRT)));

        final Player player;
        try {
            player = online(env, memory.store(), instance, account, "Restless");
            assertSavedStateApplied(player, "the first spawn");
            player.acquirable().sync(_ -> {
                player.getInventory().setItemStack(0, ItemStack.of(Material.STONE, 63));
                player.setHeldItemSlot((byte) 2);
            });

            reconfigure(env, player);
            StoreFixture.spawn(env.process(), player, instance);

            assertEquals(RESPAWN, player.getPosition(), "spawned at the respawn point");
            assertEquals(changed, slots(player), "the 46 slots");
            assertEquals(2, player.getHeldSlot(), "the held slot");
        } finally {
            chunkLoads.countDown();
        }
        await(env, () -> Boolean.TRUE.equals(player.getTag(PlayerStateSection.APPLIED)), "applied once the chunk loaded");
        assertEquals(far, player.getPosition(), "the teleport under way landed");
        assertEquals(changed, slots(player), "the 46 slots once there");
    }

    /**
     * A player that leaves while sent back to the configuration phase is saved at its respawn point, where it would
     * have spawned on its way back, not at the position the engine zeroed: it does not come back inside the ground.
     */
    @Test
    void aPlayerThatLeavesFromTheConfigurationPhaseComesBackAtItsRespawnPoint(Env env) {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player first = online(env, memory.store(), instance, account, "Wanderer");
        play(first, LEFT_AT, 2, 13, inventory());
        reconfigure(env, first);

        leave(env, memory.store(), first);
        final Player again = online(env, memory.store(), instance, account, "Wanderer");
        env.tick();

        assertEquals(RESPAWN, again.getPosition(), "the respawn point, not the zeroed position");
        assertEquals(slots(inventory()), slots(again), "the 46 slots");
        assertEquals(2, again.getHeldSlot(), "the held slot");
        assertEquals(13f, again.getHealth(), "the health");
    }

    /**
     * What a player does while the teleport to its saved position waits for the chunk is captured too, at the saved
     * position: the stone it picked up, the slot it switched to and the health it lost meanwhile are in the stored
     * section and come back with the player, the dirt it dropped is not and does not.
     */
    @Test
    void whatThePlayerDropsWhileTheTeleportIsUnderWayDoesNotComeBack(Env env) throws Exception {
        final PlayerStateFixture.Memory memory = PlayerStateFixture.memory(env, new PositionRule.Core(), PlayerStore.Options.DEFAULT);
        final Pos far = new Pos(1000.5, 40, 1000.5, 30, 0);
        final CountDownLatch chunkLoads = new CountDownLatch(1);
        final Instance instance = env.createFlatInstance(new GatedChunkLoader(far.chunkX(), far.chunkZ(), chunkLoads));
        final UUID account = UUID.randomUUID();
        seed(memory.backend(), account, saved("{\"kind\": \"core\"}", far));

        try {
            final Player dropper = online(env, memory.store(), instance, account, "Dropper");
            env.tick();
            assertEquals(false, dropper.getTag(PlayerStateSection.APPLIED), "the teleport is under way");
            dropper.acquirable().sync(_ -> {
                // What dropping the dirt does to the inventory; the item entity is the default play layer's, not
                // attached here
                dropper.getInventory().setItemStack(4, ItemStack.AIR);
                dropper.getInventory().setItemStack(0, ItemStack.of(Material.STONE, 63));
                dropper.setHeldItemSlot((byte) 2);
                dropper.setHealth(5);
            });
            leave(env, memory.store(), dropper);
        } finally {
            chunkLoads.countDown();
        }

        assertEquals("{\"world\":{\"kind\":\"core\"},"
                        + "\"pos\":{\"x\":1000.5,\"y\":40.0,\"z\":1000.5,\"yaw\":30.0,\"pitch\":0.0},"
                        + "\"inventory\":[{\"slot\":0,\"item\":\"{count:63,id:\\\"minecraft:stone\\\"}\"}],"
                        + "\"held_slot\":2,\"health\":5.0}",
                stored(memory.backend(), account),
                "what it did meanwhile, at the saved position, not the respawn point it stood at");
        final Player again = online(env, memory.store(), instance, account, "Dropper");
        await(env, () -> Boolean.TRUE.equals(again.getTag(PlayerStateSection.APPLIED)), "back at its saved position");
        assertEquals(far, again.getPosition());
        assertEquals(slots(Map.of(0, ItemStack.of(Material.STONE, 63))), slots(again), "the 46 slots");
        assertEquals(2, again.getHeldSlot(), "the held slot");
        assertEquals(5f, again.getHealth(), "the health");
    }

    /**
     * Sends a spawned player back to the configuration phase, as {@code Player#startConfigurationPhase} has the next
     * tick do: out of its instance, at the position the engine zeroed. Only before the first tick after the spawn,
     * while no tick thread owns the player yet. The client answered the known packs query of its first configuration,
     * which the next one asks again.
     */
    private static void reconfigure(Env env, Player player) {
        player.getPlayerConnection().receiveKnownPacksResponse(List.of());
        player.startConfigurationPhase();
        env.process().connection().updateWaitingPlayers();
    }

    /** Twenty ticks, five autosave periods of {@link StoreFixture#FAST}, and whatever they wrote written. */
    private static void idle(Env env, PlayerStore store, Player player) {
        for (int tick = 0; tick < 20; tick++) env.tick();
        await(env, () -> !store.isWriting(player), "the autosaves are over");
    }

    /**
     * Stone 63 in slot 0, an iron pickaxe with damage 3 in slot 1 and dirt 9 in slot 9. Built in the test, never in a
     * static field: the class initializer runs before any test started a process, and an item stack built there finds
     * its material not bound yet, which fails every test of the class.
     */
    private static Map<Integer, ItemStack> inventory() {
        return Map.of(0, ItemStack.of(Material.STONE, 63),
                1, ItemStack.builder(Material.IRON_PICKAXE).set(DataComponents.DAMAGE, 3).build(),
                9, ItemStack.of(Material.DIRT, 9));
    }

    /** Loads chunks off the requesting thread; the one chunk it gates waits for the latch, then is generated. */
    private record GatedChunkLoader(int chunkX, int chunkZ, CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int x, int z) {
            if (x == chunkX && z == chunkZ) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return null; // Let the generator take over
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }
}
