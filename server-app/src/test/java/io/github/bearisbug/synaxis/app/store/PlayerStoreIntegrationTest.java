package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.count;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player store of {@code docs/DESIGN-PROD.md} {@code REQ-PROD-005} and {@code REQ-PROD-006}
 * against a real PostgreSQL: loading, the lease, fencing, the logout save and its orphaned end,
 * sections a build cannot read, commits of several players, the freeze and the autosave
 * (§11.3, {@code API-PROD-004}, {@code API-PROD-005}; the persistence draft's P2 to P5).
 */
@EnvTest
class PlayerStoreIntegrationTest {

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

    private StoreFixture.Shard shard(Env env, String shardId) throws SQLException {
        return shard(env, shardId, StoreFixture.FAST);
    }

    private StoreFixture.Shard shard(Env env, String shardId, PlayerStore.Options options) throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, false, RedisLink.disabled(shardId), options);
        shards.add(shard);
        return shard;
    }

    /** Connects, loads, admits and spawns a player, the way the admission gate does. */
    private static Player online(Env env, StoreFixture.Shard shard, Instance instance, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(shard.store().load(player), "loaded");
        shard.store().admitted(player);
        spawn(env.process(), player, instance);
        return player;
    }

    @Test
    void aNewAccountGetsOneCharacterHeldByThisSession(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Newcomer");

        assertTrue(shard.store().load(player));

        final StoreFixture.Row row = row(database, account);
        assertNotNull(row);
        assertEquals(0, row.version());
        assertEquals(shard.sessionId(), row.lease(), "the lease is this session's");
        assertEquals(1, count(database, "SELECT count(*) FROM character WHERE account_uuid = '" + account + "'"));
        assertEquals(0, shard.store().get(player, PURSE).coins, "a section nothing is stored for starts as the default");
    }

    @Test
    void aHundredShardsRacingForANewAccountLeaveOneCharacterAndOneHolder() throws Exception {
        final PostgresBackend backend = new PostgresBackend(pool.pool());
        final List<Long> sessions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final ShardSession session = new ShardSession(pool::dedicated, "race-" + i, StoreFixture.MANUAL,
                    System::nanoTime, (_, _) -> {
                    });
            sessions.add(session.open());
            session.close();
        }
        final UUID account = UUID.randomUUID();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger acquired = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();
        for (long session : sessions) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (backend.load(account, "Racer", session) instanceof StoreBackend.LoadOutcome.Acquired)
                        acquired.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join();

        assertEquals(1, acquired.get(), "exactly one shard holds the lease");
        assertEquals(1, count(database, "SELECT count(*) FROM character WHERE account_uuid = '" + account + "'"));
    }

    @Test
    void theLogoutSaveWritesTheDirtySectionAndGivesTheLeaseBack(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Leaver");
        shard.store().get(player, PURSE).coins = 5;
        shard.store().markDirty(player, PURSE);

        player.kick(Component.text("bye"));
        await(env, () -> {
            try {
                final StoreFixture.Row row = row(database, account);
                return row != null && row.lease() == null;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, "the lease is given back");

        final StoreFixture.Row saved = row(database, account);
        assertEquals(1, saved.version(), "one write");
        assertEquals("{\"note\": \"\", \"coins\": 5}", saved.purse());
        assertEquals(2, saved.schemaVersion());

        final Player again = connect(env.process(), account, "Leaver");
        assertTrue(shard.store().load(again));
        assertEquals(5, shard.store().get(again, PURSE).coins, "the next login reads what the logout wrote");
        assertEquals(1, shard.store().versionOf(again));
    }

    @Test
    void anOlderSectionVersionIsUpgradedOnLoad(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final UUID account = UUID.randomUUID();
        storeSection(account, 1, "{\"gold\": 12}");

        final Player player = connect(env.process(), account, "Veteran");
        assertTrue(shard.store().load(player));

        assertEquals(12, shard.store().get(player, PURSE).coins, "decoded through the v1 branch");
    }

    @Test
    void aWriteAfterAnotherShardTookTheCharacterOverIsFenced(Env env) throws SQLException {
        final StoreFixture.Shard first = shard(env, "r1-c1");
        final StoreFixture.Shard second = shard(env, "r2-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player stale = online(env, first, instance, account, "Twice");
        database.execute("UPDATE shard_session SET expires_at = now() - interval '1 second' WHERE session_id = "
                + first.sessionId());

        final Player takenOver = connectAs(env, account, "Twice", second);
        second.store().get(takenOver, PURSE).coins = 9;
        second.store().markDirty(takenOver, PURSE);
        second.store().admitted(takenOver);
        assertEquals(SaveResult.SAVED, second.store().saveNow(takenOver).join());

        first.store().get(stale, PURSE).coins = 1;
        first.store().markDirty(stale, PURSE);
        assertEquals(SaveResult.FENCED, first.store().saveNow(stale).join());

        final StoreFixture.Row row = row(database, account);
        assertEquals("{\"note\": \"\", \"coins\": 9}", row.purse(), "the database holds the new holder's write");
        assertEquals(1, row.version());
        assertEquals(second.sessionId(), row.lease());
        assertEquals(List.of("synaxis:state-unavailable"), kicks(stale), "the fenced player is kicked");
        assertEquals(1, first.events("save_fenced").size());
        assertEquals(1, second.events("lease_takeover").size(), "the takeover of an expired holder is recorded");
        assertTrue(second.events("lease_takeover").getFirst().contains("previous_session_id=" + first.sessionId()),
                second.events("lease_takeover").toString());
    }

    /**
     * Loads an account on the second shard with a player the connection manager does not track:
     * one process refuses two players of one uuid, while two shards of one database are two processes.
     */
    private static Player connectAs(Env env, UUID account, String name, StoreFixture.Shard shard) {
        final Player other = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, name));
        assertTrue(shard.store().load(other));
        return other;
    }

    @Test
    void aLogoutSaveTheDatabaseRejectsIsDroppedAndTheLeaseStillGoesBack(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Hoarder");
        shard.store().get(player, PURSE).coins = 3;
        shard.store().markDirty(player, PURSE);
        assertEquals(SaveResult.SAVED, shard.store().saveNow(player).join());

        final ListAppender<ILoggingEvent> errors = capture();
        shard.store().get(player, PURSE).note = "x".repeat(70 * 1024);
        shard.store().markDirty(player, PURSE);
        player.kick(Component.text("bye"));
        await(env, () -> {
            try {
                return row(database, account).lease() == null;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, "the lease goes back after the retries");
        detach(errors);

        final StoreFixture.Row row = row(database, account);
        assertEquals(1, row.version(), "the last write that made it stands");
        assertTrue(errors.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("character_id=" + row.characterId())), "one ERROR names it");

        final Player again = connect(env.process(), account, "Hoarder");
        assertTrue(shard.store().load(again), "the next login is not locked out");
        assertEquals(3, shard.store().get(again, PURSE).coins);
    }

    @Test
    void aSectionNewerThanThisBuildOrUndecodableRefusesTheLoginAndIsLeftAlone(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final UUID newer = UUID.randomUUID();
        final UUID broken = UUID.randomUUID();
        storeSection(newer, 3, "{\"coins\": 1, \"wings\": true}");
        storeSection(broken, 2, "{\"coins\": \"many\"}");

        final Player first = connect(env.process(), newer, "Futurist");
        final Player second = connect(env.process(), broken, "Breaker");
        assertFalse(shard.store().load(first));
        assertFalse(shard.store().load(second));

        assertEquals(List.of("synaxis:state-unavailable"), kicks(first));
        assertEquals(List.of("synaxis:state-unavailable"), kicks(second));
        assertEquals("{\"coins\": 1, \"wings\": true}", row(database, newer).purse(), "not rewritten");
        assertEquals(3, row(database, newer).schemaVersion());
        assertEquals("{\"coins\": \"many\"}", row(database, broken).purse(), "not replaced by a default");
        assertNull(row(database, newer).lease(), "the lease taken for the load is given back");
        assertNull(row(database, broken).lease());
    }

    @Test
    void aCommitOfTwoPlayersWritesBothOrNeither(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final Player seller = online(env, shard, instance, a, "Seller");
        final Player buyer = online(env, shard, instance, b, "Buyer");
        final AtomicInteger applied = new AtomicInteger();

        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))), applied::incrementAndGet);
        assertEquals(SaveResult.SAVED, trade.join());
        await(env, () -> applied.get() == 1, "apply runs on the tick after the commit");
        assertEquals(1, row(database, a).version());
        assertEquals(1, row(database, b).version());

        database.execute("UPDATE character SET version = version + 1 WHERE account_uuid = '" + b + "'");
        final SaveResult mismatch = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(11)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(21))), applied::incrementAndGet).join();
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(SaveResult.FENCED, mismatch);
        assertEquals(1, applied.get(), "apply never runs for a commit that did not happen");
        assertEquals("{\"note\": \"\", \"coins\": 10}", row(database, a).purse(), "the seller is untouched");
        assertEquals(1, row(database, a).version());
        assertEquals("{\"note\": \"\", \"coins\": 20}", row(database, b).purse());
    }

    @Test
    void aDegradedShardFreezesTradesPickupsAndDrops(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final Player player = online(env, shard, instance, UUID.randomUUID(), "Frozen");
        assertFalse(shard.store().isFrozen(player));

        shard.session().beginClosing();

        assertTrue(shard.store().isFrozen(player));
        assertEquals(SaveResult.UNAVAILABLE, shard.store().commitTogether(
                Map.of(player, Map.<DataSection<?>, Object>of(PURSE, purse(1))), () -> {
                }).join());
        assertTrue(shard.events("critical_commit").getFirst().contains("cause=frozen"),
                shard.events("critical_commit").toString());
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.DIAMOND));
        final PickupItemEvent pickup = new PickupItemEvent(player, item);
        env.process().eventHandler().call(pickup);
        assertTrue(pickup.isCancelled(), "a frozen player picks nothing up");
    }

    @Test
    void anIsolatedShardKicksEveryoneWritesNothingAndTakesLoginsAgainOnceReopened(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Isolated");
        shard.store().get(player, PURSE).coins = 2;
        shard.store().markDirty(player, PURSE);
        final long first = shard.sessionId();
        database.execute("UPDATE shard_session SET expires_at = now() - interval '1 second' WHERE session_id = " + first);

        shard.session().renewOrReopen();

        assertEquals(ShardSession.State.ISOLATED, shard.session().state());
        assertEquals(List.of("synaxis:state-unavailable"), kicks(player), "everybody on an isolated shard goes");
        assertTrue(shard.events("save_fenced").getFirst().contains("cause=session_isolated"), shard.ledger().toString());
        for (int i = 0; i < 5; i++) env.tick();
        assertEquals(0, row(database, account).version(), "nothing is written by a shard that lost its session");
        assertFalse(shard.store().load(connect(env.process(), UUID.randomUUID(), "Knocker")),
                "no login while isolated");

        shard.session().renewOrReopen();
        assertEquals(ShardSession.State.ACTIVE, shard.session().state());
        final Player again = connect(env.process(), account, "Isolated");
        assertTrue(shard.store().load(again), "the new epoch takes its own expired lease over");
        assertEquals(0, shard.store().get(again, PURSE).coins, "the unsaved change of the old epoch is gone");
    }

    @Test
    void theAutosaveWritesDirtyPlayersSpreadOverTheTicksAndSkipsCleanOnes(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID dirty = UUID.randomUUID();
        final UUID clean = UUID.randomUUID();
        final Player changed = online(env, shard, instance, dirty, "Changed");
        online(env, shard, instance, clean, "Idle");
        shard.store().get(changed, PURSE).coins = 7;
        shard.store().markDirty(changed, PURSE);

        await(env, () -> {
            try {
                return row(database, dirty).version() == 1;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, "the dirty player is saved within one autosave period");
        for (int i = 0; i < 10; i++) env.tick();

        assertEquals("{\"note\": \"\", \"coins\": 7}", row(database, dirty).purse());
        assertEquals(0, row(database, clean).version(), "nothing dirty, nothing written");
        assertEquals(1, row(database, dirty).version(), "written once, not once per period");
    }

    private void storeSection(UUID account, int schemaVersion, String json) {
        database.execute("INSERT INTO account (account_uuid, last_name) VALUES ('" + account + "', 'Seeded');"
                + "INSERT INTO character (account_uuid, slot) VALUES ('" + account + "', 0);"
                + "INSERT INTO character_section (character_id, section_id, schema_version, data) "
                + "SELECT character_id, 'probe:purse', " + schemaVersion + ", '" + json + "'::jsonb FROM character "
                + "WHERE account_uuid = '" + account + "'");
    }

    private static StoreFixture.Purse purse(int coins) {
        final StoreFixture.Purse purse = new StoreFixture.Purse();
        purse.coins = coins;
        return purse;
    }

    private static ListAppender<ILoggingEvent> capture() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PlayerStore.class)).addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PlayerStore.class)).detachAppender(appender);
        appender.stop();
    }
}
