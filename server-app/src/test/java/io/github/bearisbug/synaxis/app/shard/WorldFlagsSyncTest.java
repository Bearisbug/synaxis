package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The world state flags of two channels of one region ({@code docs/DESIGN-M3.md} {@code API-M3-013},
 * §12.6, §19 「跨频道同步与 Redis 不可用时收敛」) on the shared PostgreSQL and Valkey: a write of channel 1
 * reaches channel 2 through the Redis channel of the region, and without it the read every 60 s
 * catches up; a closed channel takes neither; a malformed message is a warning.
 * <p>
 * Channel 2 mounts the baked fixture and ticks by hand; channel 1 only writes. The region gets a name
 * of its own per run, so that the Redis channel is this test's alone on a Valkey other runs share.
 * Valkey itself is not stopped: closing the Redis link of channel 2 is what cuts its subscription.
 */
@Timeout(60)
public class WorldFlagsSyncTest {

    private static final String GATE = "quest:gate";
    private static final String BRIDGE = "quest:bridge";

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @TempDir
    Path overlayRoot;
    private final String region = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
    private TestDatabase database;
    private Database pool;
    private final List<AutoCloseable> opened = new ArrayList<>();
    private final List<Env> envs = new ArrayList<>();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
    }

    @BeforeEach
    void create() throws SQLException {
        log.start();
        ((Logger) LoggerFactory.getLogger(WorldFlagsImpl.class)).addAppender(log);
        database = TestDatabase.create();
        pool = new Database(database.settings());
        Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
    }

    @AfterEach
    void drop() throws Exception {
        ((Logger) LoggerFactory.getLogger(WorldFlagsImpl.class)).detachAppender(log);
        log.stop();
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
        for (Env env : envs) env.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    private <T extends AutoCloseable> T track(T closeable) {
        opened.add(closeable);
        return closeable;
    }

    private WorldFlagsImpl channel(RedisLink redis) {
        return track(new WorldFlagsImpl(pool.pool(), redis, MapFixture.MAP_ID, region, MapFixture.BOUNDS));
    }

    private String redisChannel() {
        return "synaxis:worldflag:" + MapFixture.MAP_ID + ":" + region;
    }

    private static JsonElement json(String value) {
        return new JsonPrimitive(value);
    }

    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    private record Mounted(Env env, InstanceContainer instance) {
    }

    /** Channel 2: the fixture mounted with its flags replayed and started, ticked by hand. */
    private Mounted mount(WorldFlagsImpl flags) {
        final Env env = Env.create(config -> config.chunkViewDistance(4));
        envs.add(env);
        final ShardWorld world = ShardWorld.prepare(env.process(), release, new SynxOptions(true, 1 << 24, 1, false),
                new OverlayOptions(16_384, 10), null);
        final InstanceContainer instance = world.start(new ShutdownSequence(env.process(), new NoPlayers()), (_, _) -> {
        }, flags, overlayRoot).instance();
        instance.setChunkUnloadGraceTicks(-1);
        flags.start(instance);
        return new Mounted(env, instance);
    }

    private long subscribers() {
        final URI uri = URI.create(TestDatabase.redisUrl());
        try (Jedis single = new Jedis(uri.getHost(), uri.getPort())) {
            return single.pubsubNumSub(redisChannel()).getOrDefault(redisChannel(), 0L);
        }
    }

    private void awaitSubscribers(long count) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (subscribers() == count) return;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        fail(redisChannel() + " never had " + count + " subscribers");
    }

    private static boolean tickUntil(Env env, BooleanSupplier done, Duration within) throws InterruptedException {
        final long deadline = System.nanoTime() + within.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) return false;
            env.tick();
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return true;
    }

    @Test
    void aWriteReachesTheOtherChannelThroughRedisAndTheReadEvery60sCatchesUpWithoutIt() throws Exception {
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES "
                + "('" + MapFixture.MAP_ID + "', '" + region + "', '" + GATE + "', '\"closed\"', 1), "
                + "('" + MapFixture.MAP_ID + "', '" + region + "', '" + BRIDGE + "', '\"repaired\"', 1)");
        final RedisLink secondLink = track(RedisLink.connect(TestDatabase.redisUrl(), region + "-c2"));
        final WorldFlagsImpl second = channel(secondLink);
        second.register(GATE, WorldFlagsReplayTest::gate);
        final AtomicInteger bridgeApplied = new AtomicInteger();
        second.register(BRIDGE, value -> {
            bridgeApplied.incrementAndGet();
            return WorldFlagsReplayTest.bridge(value);
        });
        final Mounted mounted = mount(second);
        final Env env = mounted.env();
        final InstanceContainer instance = mounted.instance();
        assertEquals(Block.IRON_BARS, instance.getBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR,
                WorldFlagsReplayTest.GATE_Z));
        assertEquals(1, bridgeApplied.get(), "replayed");
        // Changed before the first tick: the read of every flag on that tick sets it back, which shows the read done
        instance.setBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR + 1, WorldFlagsReplayTest.GATE_Z, Block.STONE);
        assertTrue(tickUntil(env, () -> second.stats().conflicts() == 1, Duration.ofSeconds(10)),
                "the first tick reads every flag and checks the governed cells");
        awaitSubscribers(1);

        final WorldFlagsImpl first = channel(track(RedisLink.connect(TestDatabase.redisUrl(), region + "-c1")));
        first.register(GATE, WorldFlagsReplayTest::gate);
        assertEquals(new FlagWrite.Written(2), first.set(GATE, json("open"), 1).join());
        final long written = System.nanoTime();
        assertTrue(tickUntil(env, () -> new FlagValue(json("open"), 2).equals(second.get(GATE)), Duration.ofSeconds(1)),
                "channel 2 applies the new value within 1 s");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - written);
        assertEquals(Block.AIR, instance.getBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR,
                WorldFlagsReplayTest.GATE_Z), "took " + tookMillis + " ms");

        // Redis gone for channel 2: its subscription ends
        secondLink.close();
        awaitSubscribers(0);
        assertEquals(new FlagWrite.Written(3), first.set(GATE, json("closed"), 2).join());
        assertFalse(tickUntil(env, () -> new FlagValue(json("closed"), 3).equals(second.get(GATE)), Duration.ofSeconds(1)),
                "no message reaches channel 2");

        second.syncNow();
        assertTrue(tickUntil(env, () -> new FlagValue(json("closed"), 3).equals(second.get(GATE)), Duration.ofSeconds(10)),
                "the read every 60 s applies it");
        assertEquals(Block.IRON_BARS, instance.getBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR,
                WorldFlagsReplayTest.GATE_Z));
        assertEquals(1, bridgeApplied.get(), "a key whose version did not change is not applied again");
        assertEquals(new FlagValue(json("repaired"), 1), second.get(BRIDGE));
        assertEquals(1, second.stats().conflicts(), "the cell changed before the first tick only");
    }

    /**
     * Closed, as the stop does before the player store closes its pool and its Redis link
     * ({@code API-M3-006} step (5)), a channel applies nothing more: neither what its Redis channel
     * still brings nor what a read finds; nor does it check the governed cells.
     */
    @Test
    void aClosedChannelDropsTheMessagesAndTheReads() throws Exception {
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES "
                + "('" + MapFixture.MAP_ID + "', '" + region + "', '" + GATE + "', '\"closed\"', 1)");
        final WorldFlagsImpl second = channel(track(RedisLink.connect(TestDatabase.redisUrl(), region + "-c2")));
        second.register(GATE, WorldFlagsReplayTest::gate);
        final Mounted mounted = mount(second);
        final InstanceContainer instance = mounted.instance();
        awaitSubscribers(1);
        final FlagValue replayed = new FlagValue(json("closed"), 1);
        assertEquals(replayed, second.get(GATE));
        assertTrue(second.syncScheduled(), "the read every 60 s is scheduled");

        second.close();
        assertFalse(second.syncScheduled(), "close() cancels the read every 60 s");
        final WorldFlagsImpl first = channel(track(RedisLink.connect(TestDatabase.redisUrl(), region + "-c1")));
        first.register(GATE, WorldFlagsReplayTest::gate);
        assertEquals(new FlagWrite.Written(2), first.set(GATE, json("open"), 1).join());
        assertEquals(1, subscribers(), "still subscribed: the message reaches channel 2");
        assertFalse(tickUntil(mounted.env(), () -> !replayed.equals(second.get(GATE)), Duration.ofSeconds(1)),
                "the message is dropped");

        instance.setBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR, WorldFlagsReplayTest.GATE_Z, Block.STONE);
        second.syncNow();
        assertFalse(tickUntil(mounted.env(), () -> !replayed.equals(second.get(GATE)), Duration.ofSeconds(1)),
                "the read applies nothing");
        assertEquals(Block.STONE, instance.getBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR,
                WorldFlagsReplayTest.GATE_Z), "no governed cell is checked");
        assertEquals(Block.IRON_BARS, instance.getBlock(WorldFlagsReplayTest.GATE_X, MapFixture.FLOOR + 1,
                WorldFlagsReplayTest.GATE_Z));
        assertEquals(0, second.stats().conflicts());
        assertEquals(0, instance.chunkTicketCount(0, 0), "no application holds the chunk of the doorway");
    }

    /**
     * §28.2 「标志的测试缺口」 a message of the region's Redis channel that is not JSON is one warning that
     * names the channel, and the channel stays subscribed.
     */
    @Test
    void aMalformedMessageIsAWarningAndTheChannelStaysSubscribed() throws Exception {
        final WorldFlagsImpl second = channel(track(RedisLink.connect(TestDatabase.redisUrl(), region + "-c2")));
        second.register(GATE, WorldFlagsReplayTest::gate);
        mount(second);
        awaitSubscribers(1);

        final URI uri = URI.create(TestDatabase.redisUrl());
        try (Jedis single = new Jedis(uri.getHost(), uri.getPort())) {
            assertEquals(1, single.publish(redisChannel(), "not json"));
        }
        for (int i = 0; i < 500 && malformed().isEmpty(); i++) TimeUnit.MILLISECONDS.sleep(10);

        final List<ILoggingEvent> warnings = malformed();
        assertEquals(1, warnings.size(), log.list.toString());
        assertEquals("com.google.gson.JsonSyntaxException", warnings.getFirst().getThrowableProxy().getClassName());
        assertEquals(1, subscribers(), "still subscribed");
    }

    private List<ILoggingEvent> malformed() {
        return log.list.stream().filter(event -> event.getLevel() == Level.WARN && event.getFormattedMessage()
                .startsWith("Ignoring a malformed world flag message on " + redisChannel())).toList();
    }
}
