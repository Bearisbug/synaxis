package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.thread.TickThread;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host half of the shutdown ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008},
 * {@code API-PROD-007}, §11.2 {@code closing → released}): the save hooks on the tick threads, one
 * write per player that gives the lease back, the dead tick variant read on the calling thread, and
 * a save that does not make it in time left to the expiry of the session.
 */
@EnvTest
class ShutdownSaveIntegrationTest {

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

    private StoreFixture.Shard shard(Env env, String shardId, PlayerStore.Options options) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, false, RedisLink.disabled(shardId), options);
        shards.add(shard);
        return shard;
    }

    private static Player online(Env env, StoreFixture.Shard shard, Instance instance, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        spawn(env.process(), player, instance);
        return player;
    }

    @Test
    void theHooksRunOnTheTickThreadsAndEveryPlayerIsWrittenAndReleased(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1", StoreFixture.FAST);
        final Instance instance = env.createFlatInstance();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final Player one = online(env, shard, instance, first, "First");
        online(env, shard, instance, second, "Second");
        final List<Thread> hookThreads = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> {
            hookThreads.add(Thread.currentThread());
            shard.store().get(notice.player(), PURSE).coins = 42;
            shard.store().markDirty(notice.player(), PURSE);
        });

        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        assertTrue(shard.store().isFrozen(one), "everybody is frozen for the save");
        assertEquals(ShardSession.State.CLOSING, shard.session().state());
        await(env, saved::isDone, "the save completes once the tick threads took the snapshots");
        shard.store().onSaveEnded(false);

        assertEquals(2, hookThreads.size(), "one hook call per player");
        for (Thread thread : hookThreads) assertInstanceOf(TickThread.class, thread);
        for (UUID account : List.of(first, second)) {
            final StoreFixture.Row row = row(database, account);
            assertEquals(1, row.version());
            assertEquals("{\"note\": \"\", \"coins\": 42}", row.purse());
            assertNull(row.lease(), "every lease is given back");
        }
        assertEquals(1, shard.events("shutdown_saved").size());
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=2") && line.contains("saved=2") && line.contains("failed=0")
                && line.contains("timed_out=false"), line);
        assertEquals(ShardSession.State.RELEASED, shard.session().state());
        assertEquals(0, StoreFixture.count(database, "SELECT count(*) FROM shard_session WHERE session_id = "
                + shard.sessionId() + " AND expires_at > now()"), "released means expired");

        // The kick that follows writes nothing more: the save phase flushed everybody
        one.kick(Component.text("server stopping"));
        for (int i = 0; i < 5; i++) env.tick();
        assertEquals(1, row(database, first).version());
    }

    @Test
    void aCountdownIsAnnouncedToEveryoneOnlineAndNoCountdownIsNot(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1", StoreFixture.FAST);
        final Player player = online(env, shard, env.createFlatInstance(), UUID.randomUUID(), "Listener");

        shard.store().onGateClosed(Duration.ZERO);
        assertEquals(List.of(), StoreFixture.messages(player), "a stop without a countdown says nothing");
        shard.store().onGateClosed(Duration.ofSeconds(60));
        assertEquals(List.of("synaxis:server-stopping-in"), StoreFixture.messages(player));
    }

    @Test
    void aDeadTickIsReadOnTheShutdownThreadWithoutTheHooks(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1", StoreFixture.FAST);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Stuck");
        shard.store().get(player, PURSE).coins = 8;
        shard.store().markDirty(player, PURSE);
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", _ -> hooks.add("called"));

        // No tick runs in this test: the first call waits on a tick that never comes, the second is
        // what the engine does once the watchdog judged the process dead
        final CompletableFuture<?> waiting = shard.store().saveAll(Duration.ofSeconds(30), true);
        assertFalse(waiting.isDone());
        final CompletableFuture<?> dead = shard.store().saveAll(Duration.ofSeconds(15), false);
        dead.join();
        shard.store().onSaveEnded(false);

        assertEquals(List.of(), hooks, "no plugin code runs off its tick thread");
        final StoreFixture.Row row = row(database, account);
        assertEquals("{\"note\": \"\", \"coins\": 8}", row.purse(), "what was in the sections is saved");
        assertNull(row.lease());
    }

    @Test
    void aSaveThatMissesItsBudgetIsLeftToTheExpiryOfTheSession(Env env) throws Exception {
        final PlayerStore.Options slow = new PlayerStore.Options(Duration.ofSeconds(5), 2, 4,
                List.of(Duration.ofMillis(50)), Duration.ofMinutes(1), Duration.ofMillis(50));
        final StoreFixture.Shard shard = shard(env, "r1-c1", slow);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Blocked");
        shard.store().get(player, PURSE).coins = 4;
        shard.store().markDirty(player, PURSE);
        final long characterId = row(database, account).characterId();

        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(PlayerStore.class)).addAppender(log);
        // Another transaction holds the character row: every write of the save runs into the
        // statement timeout
        try (Connection blocker = database.connect()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT 1 FROM character WHERE character_id = " + characterId + " FOR UPDATE");
            }
            final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(3), true);
            for (int i = 0; i < 60 && !saved.isDone(); i++) {
                env.tick();
                Thread.sleep(50);
            }
            assertFalse(saved.isDone(), "the save cannot complete while the row is held");
            shard.store().onSaveEnded(true);
            // The write in flight runs into its statement timeout, and nothing retries after it
            Thread.sleep(Database.STATEMENT_TIMEOUT_MS + 500);
            assertEquals(0, StoreFixture.count(database, "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'"), "no write left waiting");
            blocker.rollback();
        } finally {
            ((Logger) LoggerFactory.getLogger(PlayerStore.class)).detachAppender(log);
        }

        assertTrue(log.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("Not saved on shutdown")
                        && event.getFormattedMessage().contains("character_id=" + characterId)),
                "each character not saved is named");
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=1") && line.contains("saved=0") && line.contains("failed=1")
                && line.contains("timed_out=true"), line);

        // The session is released, so any other shard takes the character over at once
        final StoreFixture.Shard next = shard(env, "r2-c1", StoreFixture.FAST);
        final Player again = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, "Blocked"));
        assertTrue(next.store().load(again));
        assertEquals(1, next.events("lease_takeover").size(), next.ledger().toString());
    }
}
