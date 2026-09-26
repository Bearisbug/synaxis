package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown save of players that are let in but never enter the world while it runs
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008}, §11.4 {@code gate_closed → saving}): a client
 * that never answers the known packs, or never acknowledges the end of its configuration, is
 * disconnected on the tick after the freeze, and its disconnect takes the shutdown snapshot; a
 * player in the world is not disconnected. A snapshot whose tick comes after the save phase takes
 * nothing.
 */
@EnvTest
class ShutdownSaveOutsideTheWorldIntegrationTest {

    private TestDatabase database;
    private Database pool;
    private final List<StoreFixture.Shard> shards = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        for (StoreFixture.Shard shard : shards) shard.store().close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private StoreFixture.Shard shard(Env env) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST);
        shards.add(shard);
        return shard;
    }

    /** A player loaded, let in and holding a dirty section, still in the configuration phase. */
    private static Player admitted(StoreFixture.Shard shard, Env env, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        shard.store().get(player, PURSE).coins = 6;
        shard.store().markDirty(player, PURSE);
        return player;
    }

    private static List<String> hooks(StoreFixture.Shard shard) {
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> {
            hooks.add(notice.player().getUsername());
            shard.store().get(notice.player(), PURSE).note = "hook";
            shard.store().markDirty(notice.player(), PURSE);
        });
        return hooks;
    }

    @Test
    void aPlayerStillConfiguringIsDisconnectedOnTheNextTickAndItsDisconnectSavesIt(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final Instance instance = env.createFlatInstance();
        final UUID configuring = UUID.randomUUID();
        final UUID playing = UUID.randomUUID();
        // Its client never answers the known packs: nothing else would end its configuration
        final Player waiting = admitted(shard, env, configuring, "Waiting");
        final Player inWorld = admitted(shard, env, playing, "Playing");
        spawn(env.process(), inWorld, instance);
        final List<String> hooks = hooks(shard);

        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        assertEquals(List.of(), kicks(waiting), "not before the next tick");
        await(env, saved::isDone, "the disconnect takes the snapshot of the player outside the world");
        shard.store().onSaveEnded(false);

        assertEquals(List.of("synaxis:server-stopping"), kicks(waiting));
        assertFalse(waiting.isOnline());
        assertEquals(List.of(), kicks(inWorld), "a player in the world is saved on its tick, not disconnected");
        assertTrue(inWorld.isOnline());
        assertEquals(List.of("Playing", "Waiting"), hooks.stream().sorted().toList(), "one hook call each");
        for (UUID account : List.of(configuring, playing)) {
            final StoreFixture.Row row = row(database, account);
            assertEquals(1, row.version(), "one write");
            assertEquals("{\"note\": \"hook\", \"coins\": 6}", row.purse());
            assertNull(row.lease(), "the lease is given back");
        }
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=2") && line.contains("saved=2") && line.contains("failed=0")
                && line.contains("timed_out=false"), line);
    }

    @Test
    void aPlayerThatNeverAcknowledgesTheEndOfItsConfigurationIsDisconnectedAndSaved(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard, env, account, "Silent");
        finishConfigurationUnacknowledged(env, player, instance);
        final List<String> hooks = hooks(shard);
        final List<Player> reported = new CopyOnWriteArrayList<>();
        shard.node().addListener(PlayerDisconnectEvent.class, event -> reported.add(event.getPlayer()));

        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        await(env, saved::isDone, "the disconnect takes the snapshot of the player outside the world");
        shard.store().onSaveEnded(false);

        assertEquals(List.of("synaxis:server-stopping"), kicks(player));
        assertEquals(List.of(player), reported, "the tick reports the disconnect once");
        assertEquals(List.of("Silent"), hooks, "the save hook runs once");
        final StoreFixture.Row row = row(database, account);
        assertEquals(1, row.version(), "one write");
        assertEquals("{\"note\": \"hook\", \"coins\": 6}", row.purse());
        assertNull(row.lease(), "the lease is given back");
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=1") && line.contains("saved=1") && line.contains("failed=0")
                && line.contains("timed_out=false"), line);
        assertEquals(0, shard.store().loadedCount(), "the store let the player go");
    }

    @Test
    void aSnapshotWhoseTickComesAfterTheSavePhaseTakesNothing(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard, env, account, "Behind");
        spawn(env.process(), player, env.createFlatInstance());
        final List<String> hooks = hooks(shard);

        // The budget runs out before the player's tick: its snapshot is still waiting on the scheduler
        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        shard.store().onSaveEnded(true);
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(List.of(), hooks, "no save hook after the save phase");
        assertFalse(saved.isDone());
        final StoreFixture.Row row = row(database, account);
        assertEquals(0, row.version(), "nothing written after the save phase");
        assertNull(row.purse());
        assertEquals(shard.sessionId(), row.lease(), "the lease runs out with the released session");
        assertEquals(List.of(), kicks(player), "a player in the world is not disconnected");
    }

    /**
     * The server ends the configuration of a connected player and the client never acknowledges it:
     * the connection is in the play state, the player is queued nowhere and never spawns.
     */
    private static void finishConfigurationUnacknowledged(Env env, Player player, Instance instance) {
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 42, 0));
        });
        CompletableFuture.runAsync(() -> env.process().connection().doConfiguration(player, false),
                Thread.ofVirtual()::start).join();
        final StoreFixture.RecordingConnection connection = (StoreFixture.RecordingConnection) player.getPlayerConnection();
        connection.setServerState(ConnectionState.PLAY);
    }
}
