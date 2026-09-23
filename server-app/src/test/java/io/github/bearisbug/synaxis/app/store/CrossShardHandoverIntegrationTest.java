package io.github.bearisbug.synaxis.app.store;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
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

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
