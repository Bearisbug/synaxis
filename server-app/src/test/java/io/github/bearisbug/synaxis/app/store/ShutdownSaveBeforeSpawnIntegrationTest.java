package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
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
import static io.github.bearisbug.synaxis.app.store.StoreFixture.enterPlay;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown save of a player that was let in but never entered the world
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008}, {@code API-PROD-007}, §11.3, §11.4): no tick
 * thread runs such a player's scheduler, so the snapshot the save hands to it never comes; the
 * disconnect reported after the save froze the player takes that snapshot instead. The order is the
 * test's own: the save starts before any tick reports the disconnect.
 */
@EnvTest
class ShutdownSaveBeforeSpawnIntegrationTest {

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

    @Test
    void aPlayerThatLeftInPlayBeforeItSpawnedIsSavedWhenTheTickReportsIt(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard, env, account, "Hasty");
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> {
            hooks.add(notice.player().getUsername());
            shard.store().get(notice.player(), PURSE).note = "hook";
            shard.store().markDirty(notice.player(), PURSE);
        });
        final List<Player> reported = new CopyOnWriteArrayList<>();
        shard.node().addListener(PlayerDisconnectEvent.class, event -> reported.add(event.getPlayer()));
        // In the play state and queued to join, gone before the tick that would spawn it: the
        // disconnect waits for the next tick to be reported
        enterPlay(env.process(), player, instance);
        player.getPlayerConnection().disconnect();

        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        assertEquals(List.of(), reported, "the save froze the player before its disconnect was reported");
        assertFalse(saved.isDone(), "no snapshot before a tick");
        await(env, saved::isDone, "the tick that reports the disconnect takes the shutdown snapshot");
        shard.store().onSaveEnded(false);

        assertEquals(List.of(player), reported);
        assertEquals(List.of("Hasty"), hooks, "the save hook runs once");
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
    void aPlayerThatLeavesInConfigurationAfterTheSaveFrozeItIsSavedByItsDisconnect(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard, env, account, "Early");
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> hooks.add(notice.player().getUsername()));

        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        assertFalse(saved.isDone(), "no snapshot before a tick");
        // Still configuring: the disconnect is reported at once, on the thread that drops the connection
        player.getPlayerConnection().disconnect();
        await(env, saved::isDone, "the disconnect takes the shutdown snapshot");
        shard.store().onSaveEnded(false);

        assertEquals(List.of("Early"), hooks, "the save hook runs once");
        final StoreFixture.Row row = row(database, account);
        assertEquals(1, row.version(), "one write");
        assertEquals("{\"note\": \"\", \"coins\": 6}", row.purse());
        assertNull(row.lease(), "the lease is given back");
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=1") && line.contains("saved=1") && line.contains("failed=0")
                && line.contains("timed_out=false"), line);
        assertEquals(0, shard.store().loadedCount(), "the store let the player go");
    }

    @Test
    void aDisconnectReportedAfterTheSavePhaseWritesNothing(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env);
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard, env, account, "Late");
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> hooks.add(notice.player().getUsername()));

        // The budget runs out before any tick: the save phase ends with this player not snapshotted
        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        shard.store().onSaveEnded(true);
        assertFalse(saved.isDone());
        player.getPlayerConnection().disconnect();
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(List.of(), hooks, "no save hook after the save phase");
        final StoreFixture.Row row = row(database, account);
        assertEquals(0, row.version(), "nothing written after the save phase");
        assertNull(row.purse());
        assertEquals(shard.sessionId(), row.lease(), "the lease runs out with the released session");
        assertEquals(List.of(), shard.events("save_fenced"));
    }
}
