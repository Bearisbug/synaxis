package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The freeze of the shutdown save against disconnects happening at the same time
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-008}, §16 the row of the shutdown save and
 * {@code PlayerDisconnectEvent}): a player frozen before its disconnect decides has its shutdown
 * snapshot taken by the disconnect; one whose disconnect decided first is saved by its logout save
 * and left out of the shutdown save. Either way the save completes and every character is written
 * once.
 */
@EnvTest
class ShutdownSaveRaceIntegrationTest {

    private static final int ROUNDS = 200;
    private static final int PLAYERS = 16;
    private static final Duration STUCK_AFTER = Duration.ofSeconds(2);

    private @Nullable TestDatabase database;
    private @Nullable Database pool;
    private final List<PlayerStore> stores = new ArrayList<>();

    @AfterEach
    void drop() {
        for (PlayerStore store : stores) store.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** A player loaded, let in and holding a dirty section, still in the configuration phase. */
    private static Player admitted(PlayerStore store, Env env, UUID account, String name, int coins) {
        final Player player = connect(env.process(), account, name);
        assertTrue(store.load(player), "loaded");
        store.admitted(player);
        store.get(player, PURSE).coins = coins;
        store.markDirty(player, PURSE);
        return player;
    }

    @Test
    void aDisconnectThatDecidesAfterTheFreezeTakesTheShutdownSnapshot(Env env) throws Exception {
        final TestDatabase database = TestDatabase.create();
        this.database = database;
        final Database pool = new Database(database.settings());
        this.pool = pool;
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, "r1-c1", false, RedisLink.disabled("r1-c1"),
                StoreFixture.FAST);
        stores.add(shard.store());
        final UUID account = UUID.randomUUID();
        final Player player = admitted(shard.store(), env, account, "Racer", 6);
        final List<String> hooks = new CopyOnWriteArrayList<>();
        shard.store().addShutdownHook("probe", notice -> hooks.add(notice.player().getUsername()));

        // The disconnect is under way and waits on the record's lock; the freeze happens meanwhile
        final Object lock = recordOf(shard.store(), player);
        final CompletableFuture<?> saved;
        final Thread leaving;
        synchronized (lock) {
            leaving = Thread.ofPlatform().name("leaving").start(() -> player.getPlayerConnection().disconnect());
            waitUntil(() -> blockedOnMe(leaving), "the disconnect waits on the lock of the record");
            saved = shard.store().saveAll(Duration.ofSeconds(30), true);
        }
        leaving.join(TimeUnit.SECONDS.toMillis(10));
        await(env, saved::isDone, "the disconnect that decided after the freeze takes the shutdown snapshot");
        shard.store().onSaveEnded(false);

        assertEquals(List.of("Racer"), hooks, "the save hook runs once");
        final StoreFixture.Row row = row(database, account);
        assertNotNull(row);
        assertEquals(1, row.version(), "one write");
        assertEquals("{\"note\": \"\", \"coins\": 6}", row.purse());
        assertNull(row.lease(), "the lease is given back");
        final String line = shard.events("shutdown_saved").getFirst();
        assertTrue(line.contains("online=1") && line.contains("saved=1") && line.contains("failed=0")
                && line.contains("timed_out=false"), line);
    }

    @Test
    void disconnectsAtTheSameTimeAsTheFreezeEndEveryRoundSaved(Env env) throws Exception {
        int stuck = 0;
        int neverWritten = 0;
        int writtenTwice = 0;
        int ledgerWrong = 0;
        for (int round = 0; round < ROUNDS; round++) {
            final MemoryBackend backend = new MemoryBackend();
            final List<Map<String, ?>> saves = new CopyOnWriteArrayList<>();
            final PlayerStore store = new PlayerStore(env.process(), backend, RedisLink.disabled("race"), "race", false,
                    StoreFixture.FAST, (event, attributes) -> {
                        if (event.equals("shutdown_saved")) saves.add(attributes);
                    });
            stores.add(store);
            store.register(PURSE);
            final EventNode<Event> node = EventNode.all("race-" + round);
            env.process().eventHandler().addChild(node);
            store.attach(node);
            final AtomicInteger hooks = new AtomicInteger();
            store.addShutdownHook("probe", _ -> hooks.incrementAndGet());
            final List<UUID> accounts = new ArrayList<>();
            final List<Player> players = new ArrayList<>();
            for (int i = 0; i < PLAYERS; i++) {
                final UUID account = UUID.randomUUID();
                accounts.add(account);
                players.add(admitted(store, env, account, "R" + round + "_" + i, i + 1));
            }

            // Every disconnect on a platform thread of its own, let go together with the freeze
            final CyclicBarrier start = new CyclicBarrier(PLAYERS + 1);
            final List<Thread> leaving = new ArrayList<>();
            for (Player player : players) {
                leaving.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    player.getPlayerConnection().disconnect();
                }));
            }
            start.await();
            final CompletableFuture<?> saved = store.saveAll(Duration.ofSeconds(30), true);
            for (Thread thread : leaving) thread.join(TimeUnit.SECONDS.toMillis(10));
            final long deadline = System.nanoTime() + STUCK_AFTER.toNanos();
            while ((!saved.isDone() || store.loadedCount() > 0) && System.nanoTime() < deadline) {
                env.tick();
                Thread.sleep(5);
            }
            final boolean done = saved.isDone();
            if (!done) stuck++;
            store.onSaveEnded(!done);

            for (UUID account : accounts) {
                final StoreBackend.LoadOutcome.Acquired stored =
                        (StoreBackend.LoadOutcome.Acquired) backend.load(account, "", 0);
                if (stored.version() == 0) neverWritten++;
                else if (stored.version() > 1) writtenTwice++;
            }
            // A round that completed: nothing failed, one hook per player the freeze took
            final Map<String, ?> ledger = saves.getFirst();
            if (done && (!Integer.valueOf(0).equals(ledger.get("failed")) || !ledger.get("online").equals(hooks.get())))
                ledgerWrong++;
            env.process().eventHandler().removeChild(node);
        }

        assertEquals("stuck=0 never_written=0 written_twice=0 ledger_wrong=0", "stuck=" + stuck + " never_written="
                + neverWritten + " written_twice=" + writtenTwice + " ledger_wrong=" + ledgerWrong,
                "of " + ROUNDS + " rounds of " + PLAYERS + " disconnects at the same time as the freeze");
    }

    /** The record of a player, the lock its disconnect and the freeze decide under. */
    private static Object recordOf(PlayerStore store, Player player) throws ReflectiveOperationException {
        final Field records = PlayerStore.class.getDeclaredField("records");
        records.setAccessible(true);
        final Object record = ((Map<?, ?>) records.get(store)).get(player);
        assertNotNull(record, "loaded");
        return record;
    }

    /** Whether a thread is blocked on a lock this thread holds. */
    private static boolean blockedOnMe(Thread thread) {
        final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == Thread.currentThread().threadId();
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 10 s: " + what);
            Thread.sleep(1);
        }
    }
}
