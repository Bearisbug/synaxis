package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.PURSE;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.kicks;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.row;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same account logging in on a second shard ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-004}
 * cross-shard part, {@code REQ-PROD-007}, §12.4): the kick request through Redis, the handover of
 * the lease within 5 s, the refusal when Redis is gone, and the presence keys that are only a hint.
 */
@EnvTest
class CrossShardHandoverIntegrationTest {

    private TestDatabase database;
    private Database pool;
    private RedisClient redis;
    private @Nullable JedisPubSub kickListener;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<StoreFixture.Shard> shards = new ArrayList<>();

    @BeforeEach
    void create() throws SQLException {
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.ONLINE);
        redis = RedisClient.create(URI.create(TestDatabase.redisUrl()));
    }

    @AfterEach
    void drop() {
        for (StoreFixture.Shard shard : shards) shard.store().close();
        if (kickListener != null) kickListener.punsubscribe();
        if (redis != null) redis.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private StoreFixture.Shard shard(Env env, String name, boolean withRedis, boolean displace) throws SQLException {
        final String shardId = name + "-" + run;
        final RedisLink link = withRedis ? RedisLink.connect(TestDatabase.redisUrl(), shardId) : RedisLink.disabled(shardId);
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, displace, link, StoreFixture.FAST);
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

    /** The same account on another shard: a player the connection manager of this process does not track. */
    private static Player elsewhere(Env env, UUID account, String name) {
        final StoreFixture.RecordingConnection connection = new StoreFixture.RecordingConnection(env.process());
        connection.setServerState(ConnectionState.CONFIGURATION);
        return new Player(connection, new GameProfile(account, name));
    }

    @Test
    void aLoginOnAnotherShardKicksTheOldSessionAndTakesTheCharacterOverWithinFiveSeconds(Env env) throws Exception {
        final StoreFixture.Shard first = shard(env, "a", true, true);
        final StoreFixture.Shard second = shard(env, "b", true, true);
        awaitSubscribed(first);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player old = online(env, first, instance, account, "Roamer");
        first.store().get(old, PURSE).coins = 6;
        first.store().markDirty(old, PURSE);

        final Player fresh = elsewhere(env, account, "Roamer");
        final long start = System.nanoTime();
        final CompletableFuture<Boolean> loaded = CompletableFuture.supplyAsync(() -> second.store().load(fresh),
                Thread.ofVirtual()::start);
        await(env, loaded::isDone, "the login on the second shard ends");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(loaded.join(), "the second shard gets the character");
        assertTrue(tookMillis < 5_000, "within the 5 s handover, took " + tookMillis + " ms");
        assertEquals(List.of("synaxis:logged-in-elsewhere"), kicks(old), "the old session is told why");
        assertEquals(6, second.store().get(fresh, PURSE).coins, "it reads what the first shard wrote on the way out");
        assertEquals(row(database, account).version(), second.store().versionOf(fresh), "the version it was released at");
        assertEquals(second.sessionId(), row(database, account).lease());
    }

    @Test
    void withoutRedisTheSecondLoginIsRefusedAndTheFirstStays(Env env) throws SQLException {
        final StoreFixture.Shard first = shard(env, "a", false, true);
        final StoreFixture.Shard second = shard(env, "b", false, true);
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player old = online(env, first, instance, account, "Stayer");

        final Player fresh = elsewhere(env, account, "Stayer");
        final long start = System.nanoTime();
        assertFalse(second.store().load(fresh));

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000, "refused at once");
        assertEquals(List.of("synaxis:state-unavailable"), kicks(fresh));
        assertEquals(List.of(), kicks(old), "the first session is not touched");
        assertEquals(first.sessionId(), row(database, account).lease());
    }

    @Test
    void aLoginWhoseConnectionClosedWhileTheHolderHeldOnGivesWayToTheReconnect(Env env) throws Exception {
        // The holder hears no kick request by itself: the first one is lost, the second one is
        // delivered by the test, answered like a save that takes half a second
        final StoreFixture.Shard holder = shard(env, "a", false, true);
        final String shardId = "b-" + run;
        final PlayerStore.Options twoSeconds = new PlayerStore.Options(Duration.ofSeconds(2), 2, 4,
                List.of(Duration.ofMillis(50)), Duration.ofSeconds(1), Duration.ofMillis(50));
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, true,
                RedisLink.connect(TestDatabase.redisUrl(), shardId), twoSeconds);
        shards.add(shard);
        final List<String> published = listenToKickChannels();
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player old = online(env, holder, instance, account, "Rejoin");

        final Player dropped = elsewhere(env, account, "Rejoin");
        final CompletableFuture<Boolean> droppedLoaded = CompletableFuture.supplyAsync(
                () -> shard.store().load(dropped), Thread.ofVirtual()::start);
        await(env, () -> published.size() == 1, "the first login asks the holder to let go");
        dropped.getPlayerConnection().disconnect();
        Thread.ofVirtual().start(() -> {
            try {
                while (published.size() < 2) Thread.sleep(10);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            old.kick(DisconnectReasons.LOGGED_IN_ELSEWHERE);
        });
        final Player reconnect = elsewhere(env, account, "Rejoin");
        final CompletableFuture<Boolean> reconnectLoaded = CompletableFuture.supplyAsync(
                () -> shard.store().load(reconnect), Thread.ofVirtual()::start);
        await(env, reconnectLoaded::isDone, "the reconnect ends");

        assertTrue(reconnectLoaded.join(), "the reconnect gets the character within its own 2 s");
        assertFalse(droppedLoaded.join(), "the closed connection gave up");
        assertEquals(2, published.size(), "one kick request each: " + published);
        assertEquals(List.of("synaxis:logged-in-elsewhere"), kicks(old));
        assertEquals(shard.sessionId(), row(database, account).lease());
    }

    @Test
    void anOfflineShardNeverDisplacesAndSendsNoKickRequest(Env env) throws Exception {
        final StoreFixture.Shard first = shard(env, "a", true, true);
        final StoreFixture.Shard second = shard(env, "b", true, false);
        final List<String> published = listenToKickChannels();
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();
        final Player old = online(env, first, instance, account, "Offline");

        assertFalse(second.store().load(elsewhere(env, account, "Offline")));
        Thread.sleep(300);

        assertEquals(List.of(), kicks(old));
        assertEquals(List.of(), published, "a refusal without a handover sends nothing");
    }

    @Test
    void presenceFollowsTheLoginAndAFirstLoginPublishesNothing(Env env) throws Exception {
        final StoreFixture.Shard shard = shard(env, "a", true, true);
        final List<String> published = listenToKickChannels();
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();

        final Player player = online(env, shard, instance, account, "Present");

        // Written off the login path, after the loading one
        await(env, () -> String.valueOf(redis.get("presence:" + account)).contains("\"state\":\"online\""),
                "the admission sets the presence");
        final String presence = redis.get("presence:" + account);
        assertTrue(presence.contains("\"state\":\"online\"") && presence.contains("\"shard_id\":\"a-" + run + "\""),
                presence);
        final long ttl = redis.pttl("presence:" + account);
        assertTrue(ttl > 0 && ttl <= 30_000, "a routing hint that runs out: " + ttl);
        assertEquals(List.of(), published, "a new account takes nobody's lease");

        player.kick(Component.text("bye"));
        await(env, () -> redis.get("presence:" + account) == null, "the logout clears the presence");
    }

    @Test
    void anUnreachableRedisIsOneWarningPerTenSecondsAndNeverAnError() {
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(RedisLink.class)).addAppender(log);
        try (RedisLink link = RedisLink.connect("redis://127.0.0.1:1", "down-" + run)) {
            for (int i = 0; i < 20; i++) {
                assertFalse(link.requestKick("other", UUID.randomUUID()));
                link.presence(UUID.randomUUID(), 1, "online");
                link.clearPresence(UUID.randomUUID());
            }
            assertFalse(link.isUp());
        } finally {
            ((Logger) LoggerFactory.getLogger(RedisLink.class)).detachAppender(log);
        }
        assertEquals(1, log.list.stream().filter(event -> event.getLevel() == Level.WARN).count(), log.list.toString());
        assertEquals(0, log.list.stream().filter(event -> event.getLevel() == Level.ERROR).count());
    }

    /**
     * A Redis that takes connections and never answers, as a paused container or a lost host does:
     * every round trip waits for the whole timeout. None of them is on the way of a login.
     */
    @Test
    void aRedisThatTakesConnectionsButNeverAnswersHoldsUpNoLogin(Env env) throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final List<Socket> taken = new CopyOnWriteArrayList<>();
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) taken.add(silent.accept());
                } catch (IOException _) {
                    // Closed by the test
                }
            });
            final String shardId = "silent-" + run;
            final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, true,
                    RedisLink.connect("redis://127.0.0.1:" + silent.getLocalPort(), shardId), StoreFixture.FAST);
            shards.add(shard);
            final List<CompletableFuture<Boolean>> logins = new ArrayList<>();
            final long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                final Player player = connect(env.process(), UUID.randomUUID(), "Silent" + i);
                logins.add(CompletableFuture.supplyAsync(
                        () -> shard.store().load(player) && shard.store().admitted(player), Thread.ofVirtual()::start));
            }
            CompletableFuture.allOf(logins.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(20, logins.stream().filter(CompletableFuture::join).count(), "every login is let in");
            assertTrue(tookMillis < 5_000, "all of them within one load budget, took " + tookMillis + " ms");
            for (Socket socket : taken) socket.close();
        }
    }

    /**
     * Every pooled connection taken by a subscription, which keeps its connection for as long as it
     * listens: a request waits for one at most the timeout, then fails like an unreachable Redis.
     */
    @Test
    void aRequestFindingEveryConnectionTakenGivesUpWithinTheTimeout() throws Exception {
        final String channel = "synaxis:test:pool-" + run;
        try (RedisLink link = RedisLink.connect(TestDatabase.redisUrl(), "pool-" + run)) {
            for (int i = 0; i < 8; i++) link.listenWorldFlags(channel, _ -> {
            });
            final URI uri = URI.create(TestDatabase.redisUrl());
            try (Jedis single = new Jedis(uri.getHost(), uri.getPort())) {
                for (int i = 0; i < 500 && single.pubsubNumSub(channel).getOrDefault(channel, 0L) < 8; i++)
                    Thread.sleep(10);
                assertEquals(8L, single.pubsubNumSub(channel).getOrDefault(channel, 0L), "the pool of 8 is taken");
            }
            final CompletableFuture<Boolean> sent = CompletableFuture.supplyAsync(
                    () -> link.publishWorldFlag(channel, "flag"), Thread.ofVirtual()::start);

            assertFalse(sent.get(RedisLink.TIMEOUT_MS + 3_000, TimeUnit.MILLISECONDS), "no connection, not sent");
        }
    }

    /**
     * Logins of characters another shard holds wait for it to let go without a load slot: the slots
     * only cover the statements (§17 {@code store.load}), so an unrelated login does not queue
     * behind their polls.
     */
    @Test
    void loginsWaitingForAnotherShardToLetGoLeaveTheLoadSlotsToOtherLogins(Env env) throws Exception {
        final UUID one = UUID.randomUUID();
        final UUID two = UUID.randomUUID();
        final Set<UUID> heldElsewhere = Set.of(one, two);
        final PostgresBackend postgres = new PostgresBackend(pool.pool());
        final StoreBackend backend = new StoreBackend() {
            @Override
            public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
                if (heldElsewhere.contains(account)) return new LoadOutcome.Held("c-" + run, 1);
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
        final String shardId = "b-" + run;
        final StoreFixture.Shard shard = StoreFixture.shard(env, pool, shardId, true,
                RedisLink.connect(TestDatabase.redisUrl(), shardId), StoreFixture.FAST, StoreFixture.MANUAL, backend);
        shards.add(shard);
        final List<String> published = listenToKickChannels();
        final List<CompletableFuture<Boolean>> polling = new ArrayList<>();
        int polled = 0;
        for (UUID account : heldElsewhere) {
            final Player player = elsewhere(env, account, "Polling" + polled++);
            polling.add(CompletableFuture.supplyAsync(() -> shard.store().load(player), Thread.ofVirtual()::start));
        }
        await(env, () -> published.size() == 2, "both logins asked the holder to let go and poll");

        final long start = System.nanoTime();
        final boolean loaded = shard.store().load(connect(env.process(), UUID.randomUUID(), "Unrelated"));
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(loaded, "an unrelated login loads");
        assertTrue(tookMillis < 1_000, "without waiting for the polls, took " + tookMillis + " ms");
        for (CompletableFuture<Boolean> login : polling)
            assertFalse(login.get(10, TimeUnit.SECONDS), "a character nobody let go of is refused past the budget");
    }

    private static void awaitSubscribed(StoreFixture.Shard shard) throws InterruptedException {
        final String channel = RedisLink.kickChannel(shard.store().shardId());
        final URI uri = URI.create(TestDatabase.redisUrl());
        try (Jedis single = new Jedis(uri.getHost(), uri.getPort())) {
            for (int i = 0; i < 200; i++) {
                if (single.pubsubNumSub(channel).getOrDefault(channel, 0L) > 0) return;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("the shard never subscribed to " + channel);
    }

    private List<String> listenToKickChannels() throws InterruptedException {
        final List<String> published = new CopyOnWriteArrayList<>();
        final JedisPubSub listener = new JedisPubSub() {
            @Override
            public void onPMessage(String pattern, String channel, String message) {
                if (channel.endsWith(run)) published.add(channel + " " + message);
            }
        };
        this.kickListener = listener;
        Thread.ofVirtual().start(() -> redis.psubscribe(listener, "synaxis:kick:*"));
        for (int i = 0; i < 200 && !listener.isSubscribed(); i++) Thread.sleep(10);
        return published;
    }
}
