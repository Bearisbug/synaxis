package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.count;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.enterPlay;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.saveNowOwned;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player store of {@code docs/DESIGN-PROD.md} {@code REQ-PROD-005} and {@code REQ-PROD-006}
 * against a real PostgreSQL: loading, the lease, fencing, the logout save and its orphaned end,
 * sections a build cannot read, commits of several players, the freeze and the autosave
 * (§11.3, {@code API-PROD-004}, {@code API-PROD-005}; the persistence draft's P2 to P5).
 */
@EnvTest
class PlayerStoreIntegrationTest {

    /** A section whose encode throws what a JSON encoder throws on a value that refers to itself. */
    private static final DataSection<String> UNENCODABLE = new DataSection<>() {
        @Override
        public String id() {
            return "probe:cyclic";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public String createDefault() {
            return "";
        }

        @Override
        public String decode(JsonElement json, int storedVersion) {
            return json.getAsString();
        }

        @Override
        public JsonElement encode(String value) {
            throw new StackOverflowError("the test's value that refers to itself");
        }
    };

    /** A section whose default cannot be made. */
    private static final DataSection<String> NO_DEFAULT = new DataSection<>() {
        @Override
        public String id() {
            return "probe:defaultless";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public String createDefault() {
            throw new IllegalStateException("the test's section has no default");
        }

        @Override
        public String decode(JsonElement json, int storedVersion) {
            return json.getAsString();
        }

        @Override
        public JsonElement encode(String value) {
            return new JsonPrimitive(value);
        }
    };

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
    void aPlayerThatLeavesBeforeItSpawnsIsSavedAndLetGo(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Hasty");
        assertTrue(shard.store().load(player));
        shard.store().admitted(player);
        shard.store().get(player, PURSE).coins = 3;
        shard.store().markDirty(player, PURSE);
        // In the play state and queued to join, gone before the tick that would spawn it
        enterPlay(env.process(), player, instance);

        player.getPlayerConnection().disconnect();
        await(env, () -> shard.store().loadedCount() == 0, "the logout save lets the player go");

        final StoreFixture.Row saved = row(database, account);
        assertNotNull(saved);
        assertNull(saved.lease(), "the lease is given back");
        assertEquals(1, saved.version(), "one write");
        assertEquals("{\"note\": \"\", \"coins\": 3}", saved.purse());
        final Player again = connect(env.process(), account, "Hasty");
        assertTrue(shard.store().load(again), "the next login is not refused");
        assertEquals(3, shard.store().get(again, PURSE).coins);
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
        assertEquals(SaveResult.FENCED, saveNowOwned(first.store(), stale).join());

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
        assertEquals(SaveResult.SAVED, saveNowOwned(shard.store(), player).join());

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
    void aSectionWithoutADefaultRefusesTheLoginAndGivesTheLeaseBack(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        shard.store().register(NO_DEFAULT);
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Defaultless");

        assertFalse(shard.store().load(player), "refused");

        assertEquals(List.of("synaxis:state-unavailable"), kicks(player));
        assertNull(row(database, account).lease(), "the lease taken for the load is given back");
        assertEquals(0, shard.store().loadedCount());
    }

    @Test
    void aSectionWhoseEncodeThrowsAnErrorLeavesTheRestOfTheLogoutSaveGoing(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        shard.store().register(UNENCODABLE);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Cyclic");
        shard.store().get(player, PURSE).coins = 4;
        shard.store().markDirty(player, PURSE);
        shard.store().markDirty(player, UNENCODABLE);

        player.getPlayerConnection().disconnect();

        assertLetGo(env, shard, account, "Cyclic", 4);
    }

    @Test
    void aTransferWhoseSectionEncodeThrowsAnErrorStillHandsTheRestOver(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        shard.store().register(UNENCODABLE);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");
        shard.store().get(mover, PURSE).coins = 5;
        shard.store().markDirty(mover, PURSE);
        shard.store().markDirty(mover, UNENCODABLE);

        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        await(env, handoff::isDone, "the transfer's write ends");

        assertNotNull(handoff.join(), "the sections that encode are written and the lease handed over");
        assertEquals("{\"note\": \"\", \"coins\": 5}", row(database, account).purse());
        assertNull(row(database, account).lease());
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
    void aCommitAppliesOnTheThreadThatRunsTheTickNotOnATickThread(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final Player seller = online(env, shard, instance, UUID.randomUUID(), "Seller");
        final Player buyer = online(env, shard, instance, UUID.randomUUID(), "Buyer");
        final List<Thread> appliedOn = new CopyOnWriteArrayList<>();

        assertEquals(SaveResult.SAVED, shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))),
                () -> appliedOn.add(Thread.currentThread())).join());
        await(env, () -> !appliedOn.isEmpty(), "apply runs on the tick after the commit");

        // The test thread runs the tick here: the serial part of it, where every participant may be touched
        assertEquals(List.of(Thread.currentThread()), appliedOn, "applied once, on the serial part of the tick");
    }

    @Test
    void aCommitWhoseFirstParticipantLeavesBeforeItsApplyStillAppliesOnce(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final Player seller = online(env, shard, instance, a, "Seller");
        final Player buyer = online(env, shard, instance, b, "Buyer");
        // The participant whose character id is the lowest, the first one the commit locks
        final Player first = row(database, a).characterId() < row(database, b).characterId() ? seller : buyer;
        final AtomicInteger applied = new AtomicInteger();

        holdNext.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))), applied::incrementAndGet);
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the commit is on its way to the database");
        first.getPlayerConnection().disconnect();
        await(env, first::isRemoved, "the first participant leaves while the commit is written");
        letGo.countDown();
        assertEquals(SaveResult.SAVED, trade.get(10, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(1, applied.get(), "a commit that is written applies once, whoever left meanwhile");
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

    @Test
    void aSaveRetryingFromBeforeACommitDoesNotWriteOverTheCommit(Env env) throws SQLException {
        final AtomicBoolean failNext = new AtomicBoolean();
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        final StoreBackend flaky = new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                return postgres.load(account, name, sessionId);
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                if (failNext.getAndSet(false)) throw new SQLException("the test's database hiccup");
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
        final PlayerStore.Options slowRetry = new PlayerStore.Options(Duration.ofSeconds(5), 2, 4,
                List.of(Duration.ofMillis(800)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                slowRetry, StoreFixture.MANUAL, flaky);
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player trader = online(env, shard, instance, account, "Trader");
        shard.store().get(trader, PURSE).coins = 100;
        shard.store().markDirty(trader, PURSE);
        failNext.set(true);
        assertEquals(SaveResult.UNAVAILABLE, saveNowOwned(shard.store(), trader).join(), "the first attempt fails and backs off");

        assertEquals(SaveResult.SAVED, shard.store().commitTogether(
                Map.of(trader, Map.<DataSection<?>, Object>of(PURSE, purse(50))), () -> {
                }).join(), "the trade commits while the save waits for its retry");
        final long end = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
        while (System.nanoTime() < end) {
            env.tick();
            sleep(10);
        }

        assertEquals(50, shard.store().get(trader, PURSE).coins);
        assertEquals("{\"note\": \"\", \"coins\": 50}", row(database, account).purse(),
                "the retry of the snapshot from before the trade is not written over it");
    }

    @Test
    void aLoginRefusedForTheLoadQueueGivesBackTheLeaseItWasHolding(Env env) throws SQLException {
        final PlayerStore.Options noPermits = new PlayerStore.Options(Duration.ofMillis(200), 0, 4,
                List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final StoreFixture.Shard shard = shard(env, "r1-c1", noPermits);
        final UUID transferred = UUID.randomUUID();
        // What check 6 of a transfer ticket does before the ordinary load
        assertInstanceOf(StoreBackend.LoadOutcome.Acquired.class,
                new PostgresBackend(pool.pool()).load(transferred, "Crowded", shard.sessionId()));
        assertEquals(shard.sessionId(), row(database, transferred).lease());

        assertFalse(shard.store().load(connect(env.process(), transferred, "Crowded")), "the load queue is full");
        assertNull(row(database, transferred).lease(), "a login refused for the load queue gives the lease back");
    }

    @Test
    void aLoadThatFailsAfterItsAcquireHoldsNoLease(Env env) throws SQLException {
        final StoreFixture.Shard roomy = shard(env, "r1-c1");
        final UUID broken = UUID.randomUUID();
        storeSection(broken, 2, "{\"coins\": 4}");
        database.execute("ALTER TABLE character_section RENAME TO character_section_gone");
        assertFalse(roomy.store().load(connect(env.process(), broken, "Unlucky")), "the sections cannot be read");
        assertEquals(0, count(database, "SELECT count(*) FROM character WHERE account_uuid = '" + broken
                + "' AND lease_session_id IS NOT NULL"), "a load that fails after its acquire holds no lease");
    }

    @Test
    void theAutosaveComesRoundOncePerPeriodWhateverTheNumberOnline(Env env) throws SQLException {
        final PlayerStore.Options twentyTicks = new PlayerStore.Options(Duration.ofSeconds(5), 2, 20,
                List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final StoreFixture.Shard shard = shard(env, "r1-c1", twentyTicks);
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final Player first = online(env, shard, instance, a, "Busy");
        final Player second = online(env, shard, instance, b, "Busier");

        for (int i = 0; i < 100; i++) {
            for (Player player : List.of(first, second)) {
                shard.store().get(player, PURSE).coins++;
                shard.store().markDirty(player, PURSE);
            }
            env.tick();
            // Ticks run far faster than the database here: waiting for the writes keeps a snapshot
            // from merging into the one still being written, so each round the autosave takes is
            // one write however slow the database is
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (shard.store().isWriting(first) || shard.store().isWriting(second)) {
                if (System.nanoTime() - deadline >= 0) throw new AssertionError("the autosave writes did not end within 10 s");
                sleep(1);
            }
        }

        final long total = row(database, a).version() + row(database, b).version();
        assertTrue(total >= 8 && total <= 12,
                "100 ticks of a 20 tick period save each of 2 always dirty players about 5 times, not every other tick: "
                        + total + " writes");
    }

    @Test
    void aLoginAbandonedWhileAnotherLoginOfItsAccountLoadsLeavesTheLeaseToTheOther(Env env) throws Exception {
        final CountDownLatch firstInside = new CountDownLatch(1);
        final CountDownLatch letFirstGo = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        final StoreBackend slowFirst = new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                if (loads.getAndIncrement() == 0) {
                    firstInside.countDown();
                    try {
                        letFirstGo.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                return postgres.load(account, name, sessionId);
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, slowFirst);
        shards.add(shard);
        final UUID account = UUID.randomUUID();
        final Player first = connect(env.process(), account, "Twice");
        final Player second = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, "Twice"));

        final CompletableFuture<Boolean> firstLoaded = CompletableFuture.supplyAsync(() -> shard.store().load(first),
                Thread.ofVirtual()::start);
        assertTrue(firstInside.await(5, TimeUnit.SECONDS), "the first login is loading");
        final CompletableFuture<Boolean> secondLoaded = CompletableFuture.supplyAsync(() -> shard.store().load(second),
                Thread.ofVirtual()::start);
        sleep(300);
        letFirstGo.countDown();
        assertTrue(firstLoaded.get(5, TimeUnit.SECONDS), "the first login loads");
        // What the admission gate does with a login whose connection was replaced meanwhile
        shard.store().abandon(first);
        assertTrue(secondLoaded.get(5, TimeUnit.SECONDS), "the second login loads once the first is gone");
        shard.store().admitted(second);

        assertEquals(shard.sessionId(), row(database, account).lease(),
                "the abandoned login does not give back the lease of the one that plays");
    }

    @Test
    void aTransferWhoseWriteFailsKeepsItsTurnInTheAutosave(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch failIt = new CountDownLatch(1);
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        final StoreBackend held = new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                return postgres.load(account, name, sessionId);
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                if (holdNext.getAndSet(false)) {
                    writeInside.countDown();
                    try {
                        failIt.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    throw new SQLException("the test's database hiccup");
                }
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, held);
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");

        holdNext.set(true);
        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        await(env, () -> writeInside.getCount() == 0, "the transfer's write is under way");
        // Three autosave periods of 4 ticks come round while the transfer is saving
        for (int i = 0; i < 12; i++) env.tick();
        failIt.countDown();
        await(env, handoff::isDone, "the transfer's write fails");
        assertNull(handoff.join(), "the transfer failed, the player stays");
        shard.store().get(mover, PURSE).coins = 9;
        shard.store().markDirty(mover, PURSE);

        await(env, () -> {
            try {
                return "{\"note\": \"\", \"coins\": 9}".equals(row(database, account).purse());
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, "the autosave comes round to the player after its failed transfer");
    }

    /**
     * A backend whose next write, once armed, waits inside until the test lets it go, then fails
     * or goes through.
     */
    private StoreBackend holding(AtomicBoolean holdNext, CountDownLatch writeInside, CountDownLatch letGo,
                                 AtomicBoolean failHeld) {
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        return new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                return postgres.load(account, name, sessionId);
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                if (holdNext.getAndSet(false)) {
                    writeInside.countDown();
                    try {
                        letGo.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    if (failHeld.get()) throw new SQLException("the test's database hiccup");
                }
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
    }

    /** The player left this shard: no record, the lease back, and it can log in here again. */
    private void assertLetGo(Env env, StoreFixture.Shard shard, UUID account, String name, int coins) {
        await(env, () -> {
            try {
                final StoreFixture.Row row = row(database, account);
                return shard.store().loadedCount() == 0 && row != null && row.lease() == null;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, "the record goes and the lease is given back");
        try {
            assertEquals("{\"note\": \"\", \"coins\": " + coins + "}", row(database, account).purse(),
                    "the snapshot taken for the transfer is written");
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        final Player again = connect(env.process(), account, name);
        assertTrue(shard.store().load(again), "the account logs in on this shard again");
        assertEquals(coins, shard.store().get(again, PURSE).coins);
    }

    @Test
    void aTransferWhoseWriteFailsAfterTheLogoutIsRetriedAsTheLogoutSave(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean(true)));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");
        shard.store().get(mover, PURSE).coins = 7;
        shard.store().markDirty(mover, PURSE);

        holdNext.set(true);
        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        await(env, () -> writeInside.getCount() == 0, "the transfer's write is under way");
        mover.kick(Component.text("gone"));
        for (int i = 0; i < 5; i++) env.tick();
        letGo.countDown();
        await(env, handoff::isDone, "the transfer's write fails");
        assertNull(handoff.join(), "no ticket for a write that failed");

        assertLetGo(env, shard, account, "Mover", 7);
    }

    @Test
    void aTransferWhoseWriteSucceedsAfterTheLogoutEndsOffline(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");
        shard.store().get(mover, PURSE).coins = 8;
        shard.store().markDirty(mover, PURSE);

        holdNext.set(true);
        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        await(env, () -> writeInside.getCount() == 0, "the transfer's write is under way");
        mover.kick(Component.text("gone"));
        for (int i = 0; i < 5; i++) env.tick();
        letGo.countDown();
        await(env, handoff::isDone, "the transfer's write goes through");

        assertLetGo(env, shard, account, "Mover", 8);
    }

    @Test
    void aLogoutBeforeTheTransferSnapshotsSavesAsTheLogout(Env env) throws Exception {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");
        shard.store().get(mover, PURSE).coins = 6;
        shard.store().markDirty(mover, PURSE);

        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        // Gone before the tick that would take the transfer's snapshot
        mover.remove();
        await(env, handoff::isDone, "the transfer is dropped");
        assertNull(handoff.join(), "no ticket for a player that left");

        assertLetGo(env, shard, account, "Mover", 6);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
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

    @Test
    void aLoginComingWhileAnAbandonedLoginGivesItsLeaseBackKeepsTheLeaseItTakes(Env env) throws Exception {
        final StoreFixture.ReleaseHeld backend = new StoreFixture.ReleaseHeld(pool);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, backend);
        shards.add(shard);
        final StoreFixture.Shard other = shard(env, "r2-c1");
        final UUID account = UUID.randomUUID();
        final Player first = connect(env.process(), account, "Again");
        assertTrue(shard.store().load(first), "the first login loads");

        // The admission gate abandons it; its release statement is still on its way to the database
        backend.holdNextRelease();
        final CompletableFuture<Void> abandoned = CompletableFuture.runAsync(() -> shard.store().abandon(first),
                Thread.ofVirtual()::start);
        assertTrue(backend.releasing.await(5, TimeUnit.SECONDS), "the release is under way");
        final Player second = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, "Again"));
        final CompletableFuture<Boolean> secondLoaded = CompletableFuture.supplyAsync(() -> shard.store().load(second),
                Thread.ofVirtual()::start);
        sleep(300);
        backend.letGo.countDown();
        abandoned.get(5, TimeUnit.SECONDS);
        assertTrue(secondLoaded.get(5, TimeUnit.SECONDS), "the second login loads");
        shard.store().admitted(second);

        assertEquals(shard.sessionId(), row(database, account).lease(), "the second login holds the lease it took");
        final Player elsewhere = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, "Again"));
        assertFalse(other.store().load(elsewhere), "so no other shard loads the character meanwhile");
    }

    @Test
    void aLoginAbandonedTwiceGivesItsLeaseBackOnce(Env env) throws Exception {
        final StoreFixture.ReleaseHeld backend = new StoreFixture.ReleaseHeld(pool);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, backend);
        shards.add(shard);
        final UUID account = UUID.randomUUID();
        final Player first = connect(env.process(), account, "Twice");
        assertTrue(shard.store().load(first), "the first login loads");

        // Its logout abandons it while its release is on its way, then the admission gate abandons it too
        backend.holdNextRelease();
        final CompletableFuture<Void> loggedOut = CompletableFuture.runAsync(() -> shard.store().abandon(first),
                Thread.ofVirtual()::start);
        assertTrue(backend.releasing.await(5, TimeUnit.SECONDS), "the release is under way");
        shard.store().abandon(first);
        final Player second = new Player(new StoreFixture.RecordingConnection(env.process()),
                new GameProfile(account, "Twice"));
        final CompletableFuture<Boolean> secondLoaded = CompletableFuture.supplyAsync(() -> shard.store().load(second),
                Thread.ofVirtual()::start);
        sleep(300);
        backend.letGo.countDown();
        loggedOut.get(5, TimeUnit.SECONDS);
        assertTrue(secondLoaded.get(5, TimeUnit.SECONDS), "the second login loads");
        shard.store().admitted(second);

        assertEquals(shard.sessionId(), row(database, account).lease(), "the second login holds the lease it took");
    }

    /** A host section whose capture only records the thread each snapshot takes it on. */
    private static final DataSection<String> THREADS = new DataSection<>() {
        @Override
        public String id() {
            return "synaxis:probe-threads";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public String createDefault() {
            return "";
        }

        @Override
        public String decode(JsonElement json, int storedVersion) {
            return json.getAsString();
        }

        @Override
        public JsonElement encode(String value) {
            return new JsonPrimitive(value);
        }
    };

    /**
     * A shard whose session tells the store nothing: its isolation fences no character, as for a
     * login whose record is not listed yet when the isolation comes.
     */
    private StoreFixture.Shard unlistenedShard(Env env, String shardId) throws SQLException {
        final List<String> ledger = new CopyOnWriteArrayList<>();
        final PlayerStore store = new PlayerStore(env.process(), new PostgresBackend(pool.pool()),
                RedisLink.disabled(shardId), shardId, false, StoreFixture.FAST,
                (event, attributes) -> ledger.add(event + " " + attributes));
        final ShardSession session = new ShardSession(pool::dedicated, shardId, StoreFixture.MANUAL, System::nanoTime,
                (_, _) -> {
                });
        store.useSession(session);
        store.register(PURSE);
        final EventNode<Event> node = EventNode.all("store-" + shardId);
        env.process().eventHandler().addChild(node);
        store.attach(node);
        session.open();
        final StoreFixture.Shard shard = new StoreFixture.Shard(store, session, ledger, node);
        shards.add(shard);
        return shard;
    }

    private void expire(StoreFixture.Shard shard) {
        database.execute("UPDATE shard_session SET expires_at = now() - interval '1 second' WHERE session_id = "
                + shard.sessionId());
    }

    /** The database with every write going through the test first, which may throw instead. */
    private StoreBackend writingThrough(WriteStep step) {
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        return new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                return postgres.load(account, name, sessionId);
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                step.before(writes);
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
    }

    /** What a write goes through before it reaches the database. */
    @FunctionalInterface
    private interface WriteStep {
        void before(List<StoreBackend.Write> writes) throws StoreBackend.FencedException, SQLException;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private void awaitLeaseBack(Env env, UUID account, String what) {
        await(env, () -> {
            try {
                final StoreFixture.Row row = row(database, account);
                return row != null && row.lease() == null;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }, what);
    }

    @Test
    void aSaveThatWaitedBehindAFencedWriteIsAnsweredToo(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, writingThrough(writes -> {
                    if (!holdNext.getAndSet(false)) return;
                    writeInside.countDown();
                    awaitLatch(letGo);
                    throw new StoreBackend.FencedException(writes.getFirst().characterId());
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final Player player = online(env, shard, instance, UUID.randomUUID(), "Waiter");
        shard.store().get(player, PURSE).coins = 1;
        shard.store().markDirty(player, PURSE);

        holdNext.set(true);
        final CompletableFuture<SaveResult> first = saveNowOwned(shard.store(), player);
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the first save is being written");
        final CompletableFuture<SaveResult> second = saveNowOwned(shard.store(), player);
        letGo.countDown();

        assertEquals(SaveResult.FENCED, first.get(5, TimeUnit.SECONDS));
        assertEquals(SaveResult.FENCED, second.get(5, TimeUnit.SECONDS), "the save that came in during the write is answered");
        assertEquals(1, shard.events("save_fenced").size(), shard.ledger().toString());
    }

    @Test
    void aWriteThatFindsTheSessionIsolatedIsFencedForTheIsolation(Env env) throws SQLException {
        final StoreFixture.Shard shard = unlistenedShard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final Player player = online(env, shard, instance, UUID.randomUUID(), "Isolated");
        shard.store().get(player, PURSE).coins = 2;
        shard.store().markDirty(player, PURSE);
        expire(shard);
        shard.session().renewOrReopen();
        assertEquals(ShardSession.State.ISOLATED, shard.session().state());

        assertEquals(SaveResult.FENCED, saveNowOwned(shard.store(), player).join());
        // The save is answered before the fence writes its ledger line
        await(env, () -> !shard.events("save_fenced").isEmpty(), "the character is fenced");

        assertEquals(1, shard.events("save_fenced").size(), shard.ledger().toString());
        assertTrue(shard.events("save_fenced").getFirst().contains("cause=session_isolated"), shard.ledger().toString());
    }

    @Test
    void aLoginWhoseSessionIsIsolatedWhileItAcquiresGivesTheLeaseBack(Env env) throws Exception {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        final StoreBackend slowAcquire = new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                final LoadOutcome outcome = postgres.load(account, name, sessionId);
                acquired.countDown();
                awaitLatch(letGo);
                return outcome;
            }

            @Override
            public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
                postgres.write(sessionId, writes);
            }

            @Override
            public void release(long characterId, long sessionId) throws SQLException {
                postgres.release(characterId, sessionId);
            }

            @Override
            public void releaseAccount(UUID account, long sessionId) throws SQLException {
                postgres.releaseAccount(account, sessionId);
            }
        };
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, slowAcquire);
        shards.add(shard);
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Late");
        final long epoch = shard.sessionId();

        final CompletableFuture<Boolean> loaded = CompletableFuture.supplyAsync(() -> shard.store().load(player),
                Thread.ofVirtual()::start);
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "the acquire went through");
        expire(shard);
        shard.session().renewOrReopen();
        assertEquals(ShardSession.State.ISOLATED, shard.session().state());
        shard.session().renewOrReopen();
        assertEquals(ShardSession.State.ACTIVE, shard.session().state());
        assertTrue(shard.sessionId() != epoch, "a new epoch");
        letGo.countDown();

        assertFalse(loaded.get(5, TimeUnit.SECONDS), "a login acquired under the old epoch is refused");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(player));
        assertNull(row(database, account).lease(), "the lease the old epoch took is given back");
        assertEquals(0, shard.store().loadedCount());
    }

    @Test
    void anAdmissionAfterTheSessionWasIsolatedIsRefused(Env env) throws SQLException {
        final StoreFixture.Shard shard = unlistenedShard(env, "r1-c1");
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Unlisted");
        assertTrue(shard.store().load(player));
        expire(shard);
        shard.session().renewOrReopen();
        assertEquals(ShardSession.State.ISOLATED, shard.session().state());

        assertFalse(shard.store().admitted(player), "not let in under an isolated session");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(player));
        assertNull(row(database, account).lease(), "the lease goes back");
        assertEquals(0, shard.store().loadedCount());
    }

    @Test
    void aLogoutWakesASaveBackingOffAndIsWrittenAtOnce(Env env) throws SQLException {
        final AtomicInteger failures = new AtomicInteger();
        final PlayerStore.Options backingOff = new PlayerStore.Options(Duration.ofSeconds(5), 2, 1_000_000,
                List.of(Duration.ofMillis(50), Duration.ofSeconds(3)), Duration.ofSeconds(10), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                backingOff, StoreFixture.MANUAL, writingThrough(_ -> {
                    if (failures.getAndIncrement() < 3) throw new SQLException("the test's database hiccup");
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Patient");
        shard.store().get(player, PURSE).coins = 6;
        shard.store().markDirty(player, PURSE);
        assertEquals(SaveResult.UNAVAILABLE, saveNowOwned(shard.store(), player).join(), "the first attempt fails");
        await(env, () -> failures.get() == 2, "the second attempt fails, and the save backs off for 3 s");
        sleep(100);

        final long loggedOut = System.nanoTime();
        player.kick(Component.text("bye"));
        awaitLeaseBack(env, account, "the logout save is written");

        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loggedOut);
        assertTrue(took < 1_500, "written without waiting out the 3 s backoff: took " + took + " ms");
        assertEquals("{\"note\": \"\", \"coins\": 6}", row(database, account).purse());
    }

    @Test
    void aLogoutSaveJoiningARetryingSaveCountsItsOwnFailures(Env env) throws SQLException {
        final AtomicInteger failures = new AtomicInteger();
        final PlayerStore.Options giveUpSoon = new PlayerStore.Options(Duration.ofSeconds(5), 2, 1_000_000,
                List.of(Duration.ofMillis(50)), Duration.ofMillis(500), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                giveUpSoon, StoreFixture.MANUAL, writingThrough(_ -> {
                    failures.incrementAndGet();
                    throw new SQLException("the test's database is down");
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Unlucky");
        shard.store().get(player, PURSE).coins = 6;
        shard.store().markDirty(player, PURSE);
        assertEquals(SaveResult.UNAVAILABLE, saveNowOwned(shard.store(), player).join(), "the first attempt fails");
        await(env, () -> failures.get() >= 12, "the save has been retrying longer than a logout save may");

        final long loggedOut = System.nanoTime();
        player.kick(Component.text("bye"));
        awaitLeaseBack(env, account, "the logout save is given up and the lease goes back");

        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loggedOut);
        assertTrue(took >= 400, "the logout save retried for its own 500 ms, not given up at its first failure: took "
                + took + " ms");
    }

    @Test
    void aSaveNowWakesASaveBackingOffAndIsAnsweredAtOnce(Env env) throws Exception {
        final AtomicInteger failures = new AtomicInteger();
        final PlayerStore.Options backingOff = new PlayerStore.Options(Duration.ofSeconds(5), 2, 1_000_000,
                List.of(Duration.ofMillis(50), Duration.ofSeconds(3)), Duration.ofSeconds(10), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                backingOff, StoreFixture.MANUAL, writingThrough(_ -> {
                    if (failures.getAndIncrement() < 2) throw new SQLException("the test's database hiccup");
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Eager");
        shard.store().get(player, PURSE).coins = 8;
        shard.store().markDirty(player, PURSE);
        assertEquals(SaveResult.UNAVAILABLE, saveNowOwned(shard.store(), player).join(), "the first attempt fails");
        await(env, () -> failures.get() == 2, "the second attempt fails, and the save backs off for 3 s");
        sleep(100);

        final long asked = System.nanoTime();
        final SaveResult again = saveNowOwned(shard.store(), player).get(5, TimeUnit.SECONDS);

        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked);
        assertEquals(SaveResult.SAVED, again);
        assertTrue(took < 1_000, "answered without waiting out the 3 s backoff: took " + took + " ms");
        assertEquals("{\"note\": \"\", \"coins\": 8}", row(database, account).purse());
    }

    @Test
    void aLogoutAfterATransferWhoseWriteFailedWakesTheRetryOfItsSnapshot(Env env) throws SQLException {
        final AtomicBoolean failNext = new AtomicBoolean();
        final PlayerStore.Options backingOff = new PlayerStore.Options(Duration.ofSeconds(5), 2, 1_000_000,
                List.of(Duration.ofSeconds(3)), Duration.ofSeconds(10), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                backingOff, StoreFixture.MANUAL, writingThrough(_ -> {
                    if (failNext.getAndSet(false)) throw new SQLException("the test's database hiccup");
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player mover = online(env, shard, instance, account, "Mover");
        shard.store().get(mover, PURSE).coins = 5;
        shard.store().markDirty(mover, PURSE);

        failNext.set(true);
        final CompletableFuture<PlayerStore.Handoff> handoff = shard.store().transferOut(mover);
        assertNotNull(handoff);
        await(env, handoff::isDone, "the transfer's write fails");
        assertNull(handoff.join(), "the transfer failed, the player stays");
        assertTrue(shard.store().isOnline(mover), "back to online, its snapshot waiting for a retry 3 s away");

        final long loggedOut = System.nanoTime();
        mover.kick(Component.text("bye"));
        awaitLeaseBack(env, account, "the logout save is written");

        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loggedOut);
        assertTrue(took < 1_500, "written without waiting out the 3 s backoff: took " + took + " ms");
        assertEquals("{\"note\": \"\", \"coins\": 5}", row(database, account).purse());
    }

    @Test
    void aSectionAPluginChangesInItsDisconnectListenerIsInTheLogoutSave(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Quitter");
        final List<Throwable> failed = new CopyOnWriteArrayList<>();
        // Where a plugin's global NORMAL listener sits: a child of the host node at priority 0
        final EventNode<Event> plugin = EventNode.all("probe-normal").setPriority(0);
        plugin.addListener(PlayerDisconnectEvent.class, event -> {
            try {
                shard.store().get(event.getPlayer(), PURSE).coins = 7;
                shard.store().markDirty(event.getPlayer(), PURSE);
            } catch (RuntimeException e) {
                failed.add(e);
            }
        });
        shard.node().addChild(plugin);

        player.kick(Component.text("bye"));
        awaitLeaseBack(env, account, "the logout save gives the lease back");

        assertEquals(List.of(), failed, "the listener finds the player's data");
        assertEquals("{\"note\": \"\", \"coins\": 7}", row(database, account).purse(),
                "the logout snapshot is taken after the plugin's listener");
    }

    @Test
    void aSectionAPluginChangesInItsDisconnectListenerAfterTheShutdownSaveIsNotWritten(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Stopped");
        shard.store().get(player, PURSE).coins = 3;
        shard.store().markDirty(player, PURSE);
        final CompletableFuture<?> saved = shard.store().saveAll(Duration.ofSeconds(5), true);
        await(env, saved::isDone, "the shutdown save is written");
        shard.store().onSaveEnded(false);
        final EventNode<Event> plugin = EventNode.all("probe-normal").setPriority(0);
        plugin.addListener(PlayerDisconnectEvent.class, event -> {
            try {
                shard.store().get(event.getPlayer(), PURSE).coins = 99;
                shard.store().markDirty(event.getPlayer(), PURSE);
            } catch (IllegalStateException _) {
                // The shutdown save let the player's data go already
            }
        });
        shard.node().addChild(plugin);

        player.kick(Component.text("stopping"));
        await(env, player::isRemoved, "the kicked player is gone");
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals("{\"note\": \"\", \"coins\": 3}", row(database, account).purse(),
                "what the shutdown save wrote stands: the kick comes after it");
    }

    @Test
    void aCommitWithoutParticipantsWritesNothingAndStillApplies(Env env) throws Exception {
        final AtomicInteger writes = new AtomicInteger();
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, writingThrough(_ -> writes.incrementAndGet()));
        shards.add(shard);
        final AtomicInteger applied = new AtomicInteger();

        assertEquals(SaveResult.SAVED, shard.store().commitTogether(Map.of(), applied::incrementAndGet)
                .get(5, TimeUnit.SECONDS));
        env.tick();
        env.tick();

        assertEquals(1, applied.get(), "apply runs as after a commit");
        assertEquals(0, writes.get(), "no transaction for nobody");
    }

    @Test
    void aCommitWhoseBackendThrowsSomethingElseIsAnsweredUnavailable(Env env) throws Exception {
        final AtomicBoolean broken = new AtomicBoolean();
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, writingThrough(_ -> {
                    if (broken.get()) throw new IllegalStateException("the test's backend bug");
                }));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final Player seller = online(env, shard, instance, UUID.randomUUID(), "Seller");
        final Player buyer = online(env, shard, instance, UUID.randomUUID(), "Buyer");
        final AtomicInteger applied = new AtomicInteger();

        broken.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))), applied::incrementAndGet);

        assertEquals(SaveResult.UNAVAILABLE, trade.get(5, TimeUnit.SECONDS), "answered, not left waiting");
        for (int i = 0; i < 3; i++) env.tick();
        assertEquals(0, applied.get());
        assertTrue(shard.events("critical_commit").getLast().contains("cause=db_error"),
                shard.events("critical_commit").toString());
        assertFalse(shard.store().isFrozen(seller), "the commit no longer freezes its participants");
    }

    @Test
    void aCommitOfANullOrUnencodableValueIsRefusedOnTheCallersThread(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player seller = online(env, shard, instance, account, "Careless");
        final Map<DataSection<?>, Object> nothing = new HashMap<>();
        nothing.put(PURSE, null);

        final IllegalArgumentException staged = assertThrows(IllegalArgumentException.class,
                () -> shard.store().commitTogether(Map.of(seller, nothing), () -> {
                }));
        assertTrue(staged.getMessage().contains("probe:purse") && staged.getMessage().contains("Careless"),
                staged.getMessage());
        final IllegalArgumentException typed = assertThrows(IllegalArgumentException.class,
                () -> shard.store().commitTogether(Map.of(seller, Map.<DataSection<?>, Object>of(PURSE, "a purse")), () -> {
                }));
        assertTrue(typed.getMessage().contains("probe:purse"), typed.getMessage());
        assertInstanceOf(ClassCastException.class, typed.getCause());

        assertFalse(shard.store().isFrozen(seller), "a refused commit freezes nobody");
        for (int i = 0; i < 3; i++) env.tick();
        assertEquals(0, row(database, account).version(), "nothing is written");
    }

    @Test
    void aCommitUnderWayFreezesItsParticipantsUntilItIsAnswered(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final Player seller = online(env, shard, instance, a, "Seller");
        final Player buyer = online(env, shard, instance, UUID.randomUUID(), "Buyer");

        holdNext.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))), () -> {
                });
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the commit is on its way to the database");

        assertTrue(shard.store().isFrozen(seller), "a participant of a commit under way is frozen");
        final PickupItemEvent pickup = new PickupItemEvent(seller, new ItemEntity(ItemStack.of(Material.DIAMOND)));
        env.process().eventHandler().call(pickup);
        assertTrue(pickup.isCancelled(), "it picks nothing up");
        final CompletableFuture<SaveResult> second = shard.store().commitTogether(
                Map.of(seller, Map.<DataSection<?>, Object>of(PURSE, purse(30))), () -> {
                });
        assertTrue(second.isDone(), "a second commit of the participant is answered at once");
        assertEquals(SaveResult.UNAVAILABLE, second.join());
        letGo.countDown();

        assertEquals(SaveResult.SAVED, trade.get(5, TimeUnit.SECONDS));
        assertFalse(shard.store().isFrozen(seller), "thawed once the commit is answered");
        assertEquals("{\"note\": \"\", \"coins\": 10}", row(database, a).purse(), "the first commit stands");
        assertEquals(10, shard.store().get(seller, PURSE).coins);
    }

    @Test
    void aCommitIsAnsweredOutsideTheLocksOfItsParticipants(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final Player seller = online(env, shard, instance, a, "Seller");
        final Player buyer = online(env, shard, instance, UUID.randomUUID(), "Buyer");

        holdNext.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(Map.of(
                seller, Map.<DataSection<?>, Object>of(PURSE, purse(10)),
                buyer, Map.<DataSection<?>, Object>of(PURSE, purse(20))), () -> {
                });
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the commit is on its way to the database");
        // Attached before the answer, so it runs where the answer is given: it commits the seller again and waits
        final CompletableFuture<SaveResult> again = trade.thenApply(_ -> shard.store().commitTogether(
                Map.of(seller, Map.<DataSection<?>, Object>of(PURSE, purse(11))), () -> {
                }).orTimeout(5, TimeUnit.SECONDS).join());
        letGo.countDown();

        assertEquals(SaveResult.SAVED, trade.get(5, TimeUnit.SECONDS));
        assertEquals(SaveResult.SAVED, again.get(10, TimeUnit.SECONDS), "the callback's commit takes the locks the first let go");
        assertEquals("{\"note\": \"\", \"coins\": 11}", row(database, a).purse());
    }

    @Test
    void aCommitIsNotChangedByWhatItsCallerDoesToTheMapsAfterwards(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final Player seller = online(env, shard, instance, a, "Seller");
        final Player buyer = online(env, shard, instance, b, "Buyer");
        final Map<Player, Map<DataSection<?>, Object>> staged = new HashMap<>();
        staged.put(seller, new HashMap<>(Map.<DataSection<?>, Object>of(PURSE, purse(10))));
        staged.put(buyer, new HashMap<>(Map.<DataSection<?>, Object>of(PURSE, purse(20))));

        holdNext.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(staged, () -> {
        });
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the commit is on its way to the database");
        // A caller that reuses its maps once the call returned
        staged.get(buyer).put(PURSE, purse(99));
        staged.remove(seller);
        letGo.countDown();

        assertEquals(SaveResult.SAVED, trade.get(5, TimeUnit.SECONDS), "written, and answered so");
        assertEquals(10, shard.store().get(seller, PURSE).coins);
        assertEquals(20, shard.store().get(buyer, PURSE).coins, "the value staged at the call");
        assertEquals("{\"note\": \"\", \"coins\": 20}", row(database, b).purse());
        shard.store().markDirty(seller, PURSE);
        shard.store().markDirty(buyer, PURSE);
        assertEquals(SaveResult.SAVED, saveNowOwned(shard.store(), seller).join(), "the seller's version is the database's");
        assertEquals(SaveResult.SAVED, saveNowOwned(shard.store(), buyer).join(), "the buyer's version is the database's");
    }

    @Test
    void aSaveNowFromAnotherThreadTakesItsSnapshotOnThePlayersTickThread(Env env) throws Exception {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final List<Thread> capturedOn = new CopyOnWriteArrayList<>();
        shard.store().registerHostSection(THREADS, _ -> {
            capturedOn.add(Thread.currentThread());
            return null;
        });
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Async");
        shard.store().get(player, PURSE).coins = 4;
        shard.store().markDirty(player, PURSE);
        capturedOn.clear();

        final AtomicReference<CompletableFuture<SaveResult>> asked = new AtomicReference<>();
        Thread.ofVirtual().start(() -> asked.set(shard.store().saveNow(player))).join();
        final CompletableFuture<SaveResult> save = asked.get();
        await(env, save::isDone, "the save ends");

        assertEquals(SaveResult.SAVED, save.join());
        assertFalse(capturedOn.isEmpty(), "the snapshot captured the host section");
        final Thread owner = player.acquirable().assignedThread();
        assertEquals(List.of(), capturedOn.stream().filter(thread -> thread != owner).toList(),
                "every capture on " + owner);
        assertEquals("{\"note\": \"\", \"coins\": 4}", row(database, account).purse());
    }

    @Test
    void aSaveNowWhosePlayerIsFrozenByItsTickNeverReachedTheDatabase(Env env) throws Exception {
        final AtomicBoolean holdNext = new AtomicBoolean();
        final CountDownLatch writeInside = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST, StoreFixture.MANUAL, holding(holdNext, writeInside, letGo, new AtomicBoolean()));
        shards.add(shard);
        final Instance instance = env.createFlatInstance();
        final Player seller = online(env, shard, instance, UUID.randomUUID(), "Seller");
        final CompletableFuture<SaveResult> save = saveNowElsewhere(shard.store(), seller);

        holdNext.set(true);
        final CompletableFuture<SaveResult> trade = shard.store().commitTogether(
                Map.of(seller, Map.<DataSection<?>, Object>of(PURSE, purse(10))), () -> {
                });
        assertTrue(writeInside.await(10, TimeUnit.SECONDS), "the commit freezes the seller before its tick");
        await(env, save::isDone, "the seller's tick answers the save");
        letGo.countDown();

        assertEquals(SaveResult.UNAVAILABLE, save.join());
        assertEquals(SaveResult.SAVED, trade.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("cause=frozen"), saveNowCauses(shard), "not a database failure: " + shard.ledger());
    }

    @Test
    void aSaveNowWhosePlayerLeavesBeforeItsTickNeverReachedTheDatabase(Env env) throws Exception {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player player = online(env, shard, instance, account, "Leaver");
        shard.store().get(player, PURSE).coins = 3;
        shard.store().markDirty(player, PURSE);

        // Kicked first: the engine removes the player on its next tick, ahead of the snapshot
        player.kick(Component.text("bye"));
        final CompletableFuture<SaveResult> save = saveNowElsewhere(shard.store(), player);
        awaitLeaseBack(env, account, "the logout save is written");

        assertEquals(SaveResult.UNAVAILABLE, save.get(15, TimeUnit.SECONDS));
        assertEquals(List.of("cause=frozen"), saveNowCauses(shard), "not a database failure: " + shard.ledger());
        assertEquals("{\"note\": \"\", \"coins\": 3}", row(database, account).purse(), "the logout save wrote it");
    }

    /** {@code saveNow} from a virtual thread, which owns no player, as a plugin's {@code runAsync} calls it. */
    private static CompletableFuture<SaveResult> saveNowElsewhere(PlayerStore store, Player player)
            throws InterruptedException {
        final AtomicReference<CompletableFuture<SaveResult>> asked = new AtomicReference<>();
        Thread.ofVirtual().start(() -> asked.set(store.saveNow(player))).join();
        return asked.get();
    }

    /** The cause of every {@code EVT:critical_commit} line of a {@code saveNow}. */
    private static List<String> saveNowCauses(StoreFixture.Shard shard) {
        return shard.events("critical_commit").stream().filter(line -> line.contains("kind=save_now"))
                .map(line -> line.replaceAll(".*(cause=[a-z_]*).*", "$1")).toList();
    }

    @Test
    void aSectionThatNeverEncodesReportsOncePerBudget(Env env) throws SQLException {
        final StoreFixture.Shard shard = shard(env, "r1-c1");
        shard.store().register(UNENCODABLE);
        final Instance instance = env.createFlatInstance();
        final Player player = online(env, shard, instance, UUID.randomUUID(), "Cyclic");
        final ListAppender<ILoggingEvent> lines = capture();
        try {
            for (int i = 0; i < 10; i++) {
                shard.store().markDirty(player, UNENCODABLE);
                assertEquals(SaveResult.SAVED, saveNowOwned(shard.store(), player).join());
            }
        } finally {
            detach(lines);
        }

        assertEquals(1, lines.list.stream()
                .filter(line -> line.getFormattedMessage().startsWith("Encoding a section failed")).count(),
                "ten snapshots, one line within the budget's 10 s");
    }
}
