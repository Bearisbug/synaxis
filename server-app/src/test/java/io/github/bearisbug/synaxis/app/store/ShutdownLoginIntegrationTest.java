package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logins that are under way when the shutdown save starts ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-008}, §11.2 {@code closing}, §11.3 {@code loading → offline}, §16): a login
 * waiting for a load slot, for the same account's previous login to leave or for another shard to
 * let go takes no lease once the shard is closing, one whose acquire was under way gives it back,
 * and one whose admission comes after the freeze is refused instead of playing outside of the
 * shutdown save. Past the save phase the store writes nothing, releases included.
 */
@EnvTest
class ShutdownLoginIntegrationTest {

    private static final int ROUNDS = 100;
    private static final int PLAYERS = 16;

    private @Nullable TestDatabase database;
    private @Nullable Database pool;
    private final List<PlayerStore> stores = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        final TestDatabase created = TestDatabase.create();
        database = created;
        pool = new Database(created.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() {
        for (PlayerStore store : stores) store.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private StoreFixture.Shard shard(Env env, String shardId, RedisLink redis, Held backend, PlayerStore.Options options)
            throws SQLException {
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool(), shardId, true, redis, options,
                StoreFixture.MANUAL, backend);
        stores.add(shard.store());
        return shard;
    }

    private Database pool() {
        final Database created = pool;
        assertNotNull(created);
        return created;
    }

    private StoreFixture.@Nullable Row row(UUID account) throws SQLException {
        final TestDatabase created = database;
        assertNotNull(created);
        return StoreFixture.row(created, account);
    }

    @Test
    void aLoginWaitingForTheSameAccountToLeaveIsRefusedOnceTheSavePhaseStarted(Env env) throws Exception {
        final Held backend = new Held(pool());
        final StoreFixture.Shard shard = shard(env, "r1-c1", RedisLink.disabled("r1-c1"), backend, StoreFixture.FAST);
        final PlayerStore store = shard.store();
        final UUID account = UUID.randomUUID();
        final Player first = connect(env.process(), account, "Twice");
        assertTrue(store.load(first), "loaded");
        store.admitted(first);
        store.get(first, PURSE).coins = 5;
        store.markDirty(first, PURSE);
        // Its logout save is on its way to the database and stays there until the test lets it go
        backend.holdNextWrite();
        first.getPlayerConnection().disconnect();
        assertTrue(backend.writeHeld.await(10, TimeUnit.SECONDS), "the logout save is under way");

        final Player second = connect(env.process(), account, "Twice");
        final CompletableFuture<Boolean> loaded = new CompletableFuture<>();
        final Thread loading = Thread.ofVirtual().start(() -> loaded.complete(store.load(second)));
        waitUntil(() -> loading.getState() == Thread.State.TIMED_WAITING, "the login waits for the previous one to leave");

        final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(30), true);
        assertTrue(saved.isDone(), "nobody is online to save");
        store.onSaveEnded(false);
        backend.writeLetGo.countDown();

        assertFalse(loaded.get(10, TimeUnit.SECONDS), "refused: the shard stopped taking logins while it waited");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(second));
        assertEquals(1, backend.loads(account), "the second login never reached the database");
        assertEquals(0, backend.releases(), "nothing is released past the save phase");
        final StoreFixture.Row row = row(account);
        assertNotNull(row);
        assertEquals(1, row.version(), "only the logout save wrote");
        assertEquals("{\"note\": \"\", \"coins\": 5}", row.purse());
        assertNull(row.lease(), "no lease is left on the released session");
        assertEquals(0, store.loadedCount());
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=0") && line.contains("saved=0") && line.contains("failed=0"), line);
    }

    @Test
    void aLoginWaitingForALoadSlotTakesNoLeaseAndOneWhoseAcquireWasUnderWayGivesItBack(Env env) throws Exception {
        final Held backend = new Held(pool());
        final PlayerStore.Options oneSlot = new PlayerStore.Options(Duration.ofSeconds(5), 1, 4,
                List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final PlayerStore store = shard(env, "r1-c1", RedisLink.disabled("r1-c1"), backend, oneSlot).store();
        final UUID acquiring = UUID.randomUUID();
        final UUID queued = UUID.randomUUID();
        final Player first = connect(env.process(), acquiring, "Slot");
        final Player second = connect(env.process(), queued, "Queue");
        // The first login holds the only slot: its statements ran, its answer is on the way back
        backend.holdLoadOf(acquiring);
        final CompletableFuture<Boolean> firstLoaded = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> firstLoaded.complete(store.load(first)));
        assertTrue(backend.loadHeld.await(10, TimeUnit.SECONDS), "the first login acquired");
        final CompletableFuture<Boolean> secondLoaded = new CompletableFuture<>();
        final Thread waiting = Thread.ofVirtual().start(() -> secondLoaded.complete(store.load(second)));
        waitUntil(() -> waiting.getState() == Thread.State.TIMED_WAITING, "the second login waits for the slot");

        final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(30), true);
        assertTrue(saved.isDone(), "nobody is online to save");
        backend.loadLetGo.countDown();

        assertFalse(firstLoaded.get(10, TimeUnit.SECONDS), "its acquire was under way when the save phase started");
        assertFalse(secondLoaded.get(10, TimeUnit.SECONDS), "it waited for its slot until the save phase started");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(first));
        assertEquals(List.of("synaxis:state-unavailable"), kicks(second));
        final StoreFixture.Row acquired = row(acquiring);
        assertNotNull(acquired);
        assertNull(acquired.lease(), "the lease taken under way went back");
        assertEquals(0, backend.loads(queued), "the queued login never reached the database");
        assertNull(row(queued), "nor made a character");
        assertEquals(0, store.loadedCount());
        store.onSaveEnded(false);
    }

    @Test
    void aLoginPollingForAnotherShardToLetGoTakesNoLeaseOnceTheSavePhaseStarted(Env env) throws Exception {
        final String run = UUID.randomUUID().toString().substring(0, 8);
        // The holder never hears the kick request: it lets go when the test says so
        final String holderId = "a-" + run;
        final PlayerStore holder = shard(env, holderId, RedisLink.disabled(holderId), new Held(pool()),
                StoreFixture.FAST).store();
        final String pollingId = "b-" + run;
        final Held backend = new Held(pool());
        final StoreFixture.Shard polling = shard(env, pollingId, RedisLink.connect(TestDatabase.redisUrl(), pollingId),
                backend, StoreFixture.FAST);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player old = connect(env.process(), account, "Held");
        assertTrue(holder.load(old), "loaded on the holder");
        holder.admitted(old);
        spawn(env.process(), old, instance);

        final Player fresh = elsewhere(env, account, "Held");
        final CompletableFuture<Boolean> loaded = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> loaded.complete(polling.store().load(fresh)));
        waitUntil(() -> backend.loads(account) >= 2, "the login polls for the holder to let go");
        final CompletableFuture<?> saved = polling.store().saveAll(Duration.ofSeconds(30), true);
        assertTrue(saved.isDone(), "nobody is online to save");
        polling.store().onSaveEnded(false);
        final int loadsAtTheSave = backend.loads(account);
        // The holder lets go now: a login still polling would take the character under the released session
        old.kick(DisconnectReasons.LOGGED_IN_ELSEWHERE);
        StoreFixture.await(env, () -> holder.loadedCount() == 0, "the holder's logout save gives the lease back");

        assertFalse(loaded.get(10, TimeUnit.SECONDS), "refused: the shard stopped taking logins while it polled");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(fresh));
        assertTrue(backend.loads(account) <= loadsAtTheSave + 1,
                "at most the acquire under way when the save phase started: " + backend.loads(account));
        final StoreFixture.Row row = row(account);
        assertNotNull(row);
        assertNull(row.lease(), "no lease on the released session");
    }

    @Test
    void anAdmissionAfterTheFreezeIsRefusedAndGivesTheLeaseBack(Env env) throws Exception {
        final Held backend = new Held(pool());
        final StoreFixture.Shard shard = shard(env, "r1-c1", RedisLink.disabled("r1-c1"), backend, StoreFixture.FAST);
        final PlayerStore store = shard.store();
        final UUID account = UUID.randomUUID();
        final Player player = connect(env.process(), account, "Late");
        assertTrue(store.load(player), "loaded before the save phase");
        store.get(player, PURSE).coins = 3;
        store.markDirty(player, PURSE);
        final List<String> hooks = new CopyOnWriteArrayList<>();
        store.addShutdownHook("probe", notice -> hooks.add(notice.player().getUsername()));

        final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(30), true);
        assertTrue(saved.isDone(), "a login still loading is not frozen");
        store.admitted(player);
        for (int i = 0; i < 3; i++) env.tick();
        store.onSaveEnded(false);

        assertEquals(List.of("synaxis:state-unavailable"), kicks(player),
                "refused: let in now it would play outside of the shutdown save");
        assertFalse(player.isOnline());
        assertEquals(List.of(), hooks, "not part of the shutdown save");
        final StoreFixture.Row row = row(account);
        assertNotNull(row);
        assertEquals(0, row.version(), "nothing written");
        assertNull(row.purse());
        assertNull(row.lease(), "the lease goes back");
        assertEquals(0, store.loadedCount());
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=0") && line.contains("saved=0") && line.contains("failed=0"), line);
    }

    @Test
    void admissionsAtTheSameTimeAsTheFreezeAreEitherSavedWithItOrRefused(Env env) throws Exception {
        int wrong = 0;
        int frozen = 0;
        int refused = 0;
        final List<String> examples = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            final MemoryBackend backend = new MemoryBackend();
            final List<Map<String, ?>> saves = new CopyOnWriteArrayList<>();
            final PlayerStore store = new PlayerStore(env.process(), backend, RedisLink.disabled("race"), "race", false,
                    StoreFixture.FAST, (event, attributes) -> {
                        if (event.equals("shutdown_saved")) saves.add(attributes);
                    });
            stores.add(store);
            store.register(PURSE);
            final EventNode<Event> node = EventNode.all("admission-race-" + round);
            env.process().eventHandler().addChild(node);
            store.attach(node);
            final List<UUID> accounts = new ArrayList<>();
            final List<Player> players = new ArrayList<>();
            for (int i = 0; i < PLAYERS; i++) {
                final UUID account = UUID.randomUUID();
                final Player player = connect(env.process(), account, "A" + round + "_" + i);
                assertTrue(store.load(player), "loaded");
                store.get(player, PURSE).coins = i + 1;
                store.markDirty(player, PURSE);
                accounts.add(account);
                players.add(player);
            }

            // Every admission on a platform thread of its own, let go together with the freeze
            final CyclicBarrier start = new CyclicBarrier(PLAYERS + 1);
            final List<Thread> admitting = new ArrayList<>();
            for (Player player : players) {
                admitting.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    store.admitted(player);
                }));
            }
            start.await();
            final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(30), true);
            for (Thread thread : admitting) thread.join(TimeUnit.SECONDS.toMillis(10));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!saved.isDone() || store.loadedCount() > 0) && System.nanoTime() < deadline) {
                env.tick();
                Thread.sleep(5);
            }
            store.onSaveEnded(!saved.isDone());

            int online = 0;
            for (int i = 0; i < PLAYERS; i++) {
                final long version = ((StoreBackend.LoadOutcome.Acquired) backend.load(accounts.get(i), "", 0)).version();
                final List<String> kicked = kicks(players.get(i));
                // Let in before the freeze: frozen with everybody, disconnected by the save phase
                // since it never entered the world, saved once. Otherwise refused, nothing written
                final boolean frozenWithEverybody = kicked.equals(List.of("synaxis:server-stopping"));
                final boolean refusedByTheStore = kicked.equals(List.of("synaxis:state-unavailable"));
                final boolean ok = frozenWithEverybody ? version == 1 : refusedByTheStore && version == 0;
                if (frozenWithEverybody) online++;
                if (refusedByTheStore) refused++;
                if (!ok) {
                    wrong++;
                    if (examples.size() < 5) examples.add("version=" + version + " kicks=" + kicked);
                }
            }
            frozen += online;
            final Map<String, ?> ledger = saves.getFirst();
            if (!ledger.get("online").equals(online) || !ledger.get("saved").equals(online) || store.loadedCount() > 0) {
                wrong++;
                if (examples.size() < 5) examples.add("ledger=" + ledger + " loaded=" + store.loadedCount());
            }
            env.process().eventHandler().removeChild(node);
        }

        assertEquals(0, wrong, "of " + ROUNDS + " rounds of " + PLAYERS + " admissions at the same time as the freeze "
                + "(frozen=" + frozen + " refused=" + refused + "): " + examples);
    }

    /** The same account on another shard: a player the connection manager of this process does not track. */
    private static Player elsewhere(Env env, UUID account, String name) {
        final StoreFixture.RecordingConnection connection = new StoreFixture.RecordingConnection(env.process());
        connection.setServerState(ConnectionState.CONFIGURATION);
        return new Player(connection, new GameProfile(account, name));
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 10 s: " + what);
            Thread.sleep(1);
        }
    }

    /**
     * PostgreSQL with holds: the next write, or the load of one account once its statements ran,
     * stays on its way until the test lets it go. Loads are counted per account, release
     * statements in all.
     */
    static final class Held implements StoreBackend {
        final CountDownLatch writeHeld = new CountDownLatch(1);
        final CountDownLatch writeLetGo = new CountDownLatch(1);
        final CountDownLatch loadHeld = new CountDownLatch(1);
        final CountDownLatch loadLetGo = new CountDownLatch(1);
        private final AtomicBoolean holdWrite = new AtomicBoolean();
        private volatile @Nullable UUID holdLoad;
        private final Map<UUID, AtomicInteger> loads = new ConcurrentHashMap<>();
        private final AtomicInteger releases = new AtomicInteger();
        private final PostgresBackend postgres;

        Held(Database pool) {
            this.postgres = new PostgresBackend(pool.pool());
        }

        void holdNextWrite() {
            holdWrite.set(true);
        }

        void holdLoadOf(UUID account) {
            holdLoad = account;
        }

        int loads(UUID account) {
            final AtomicInteger count = loads.get(account);
            return count == null ? 0 : count.get();
        }

        int releases() {
            return releases.get();
        }

        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
            loads.computeIfAbsent(account, _ -> new AtomicInteger()).incrementAndGet();
            final LoadOutcome outcome = postgres.load(account, name, sessionId);
            if (account.equals(holdLoad)) {
                holdLoad = null;
                loadHeld.countDown();
                await(loadLetGo);
            }
            return outcome;
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws FencedException, SQLException {
            if (holdWrite.compareAndSet(true, false)) {
                writeHeld.countDown();
                await(writeLetGo);
            }
            postgres.write(sessionId, writes);
        }

        @Override
        public void release(long characterId, long sessionId) throws SQLException {
            releases.incrementAndGet();
            postgres.release(characterId, sessionId);
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) throws SQLException {
            releases.incrementAndGet();
            postgres.releaseAccount(account, sessionId);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("the test never let it go");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
