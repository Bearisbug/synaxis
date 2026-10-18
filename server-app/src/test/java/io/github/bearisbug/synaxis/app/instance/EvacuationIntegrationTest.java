package io.github.bearisbug.synaxis.app.instance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.event.instance.AddEntityToInstanceEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance.State;
import io.github.bearisbug.synaxis.plugin.instance.PlayInstanceStateEvent;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLAT;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.SPAWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evacuation of a closing instance with real players ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006},
 * internal task {@code play.instance.evacuate}): each player gets their exit, the instance is
 * unregistered once nobody is left, and whoever is still inside when the evacuation time runs out is
 * kicked with {@code synaxis:match-unavailable}. The evacuation time comes from the registry's
 * constructor and runs on a clock the test moves.
 */
@EnvTest
class EvacuationIntegrationTest {

    private static final Duration EVACUATION = Duration.ofSeconds(5);
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    /** Far off a map of radius 1: landing there loads chunks the map does not have. */
    private static final Pos OFF_THE_MAP = new Pos(1000.5, InstanceFixture.FLOOR, 1000.5);

    private final InstanceFixture.Ledger ledger = new InstanceFixture.Ledger();
    private final AtomicLong clock = new AtomicLong();

    private PluginInstances instances(Env env) {
        InstanceFixture.registerMatchDimension(env.process());
        return new InstanceRegistry(env.process(), ledger, InstanceFixture.scenes(env.process()), Thread.currentThread(),
                new InstanceRegistry.Durations(Duration.ofSeconds(30), EVACUATION), clock::get).forPlugin("evacuation");
    }

    /** A player who joined in an unmanaged world and was moved into {@code target}. */
    private static Player joinInto(Env env, ManagedInstance target, TestConnection connection) {
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
        final CompletableFuture<Boolean> moved = target.move(player, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");
        assertTrue(moved.join());
        assertSame(target.instance(), player.getInstance());
        return player;
    }

    private static List<String> kickKeys(Collector<DisconnectPacket> disconnects) {
        return disconnects.collect().stream()
                .map(packet -> assertInstanceOf(TranslatableComponent.class, packet.message()).key())
                .toList();
    }

    /**
     * Starts a move into {@code target}, whose chunks off the map wait for a latch while it loads chunks
     * on demand, and runs it as far as those chunks: the move passed the state check and is on its way.
     */
    private static CompletableFuture<Boolean> onItsWay(Env env, ManagedInstance target, Player player) {
        final Instance from = player.getInstance();
        target.instance().enableAutoChunkLoad(true);
        final CompletableFuture<Boolean> moved = target.move(player, SPAWN);
        for (int tick = 0; tick < 3; tick++) env.tick();
        assertFalse(moved.isDone(), "the move did not wait for the chunks off the map");
        assertSame(from, player.getInstance());
        return moved;
    }

    /** §11.1 {@code OPEN → CLOSED} by {@code cancel}: the exit first, the unregistration once the player is out. */
    @Test
    void cancelSendsThePlayerToItsExitAndUnregistersOnceTheyAreOut(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final Player player = joinInto(env, arena, env.createConnection());

        arena.cancel(_ -> new Exit.ToInstance(lobby, SPAWN));

        assertEquals(State.CLOSED, arena.state(), "closed as soon as it is cancelled");
        assertEquals(List.of(lobby), instances.owned());
        assertTrue(arena.instance().isRegistered(), "unregistered with a player still inside");
        assertTrue(env.tickWhile(() -> arena.instance().isRegistered(), TEN_SECONDS), "never unregistered");
        assertSame(lobby.instance(), player.getInstance());
        assertTrue(player.isOnline());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals("cancelled", closed.get("cause"));
        assertEquals(1, closed.get("players"));
        assertEquals(1, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
        assertEquals(arena.instanceId().toString(), ledger.last(lobby, "play_instance_moved").get("from_instance_id"));
    }

    /** {@code API-PLAY-006} 撤离: still inside when the evacuation time is up, kicked with match-unavailable. */
    @Test
    void whoeverIsStillInsideWhenTheEvacuationRunsOutIsKicked(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance far = instances.create(new InstanceSpec("far", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            far.instance().enableAutoChunkLoad(true);
            final TestConnection connection = env.createConnection();
            final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
            final Player player = joinInto(env, arena, connection);
            arena.start();
            arena.end(EndCause.TIME_LIMIT);

            // The move of the exit waits for the chunks off the map, so the player stays where they are
            arena.settled(_ -> new Exit.ToInstance(far, SPAWN));
            for (int tick = 0; tick < 5; tick++) env.tick();
            clock.addAndGet(EVACUATION.toNanos() - 1);
            env.tick();
            assertTrue(((ManagedInstanceImpl) far).movesPending(), "the move of the exit did not wait for the chunks");
            assertTrue(arena.instance().isRegistered(), "unregistered before the evacuation time was up");
            assertTrue(player.isOnline(), "kicked before the evacuation time was up");

            clock.addAndGet(1);
            env.tick();

            assertFalse(player.isOnline());
            assertFalse(arena.instance().isRegistered());
            assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals("time_limit", closed.get("cause"));
            assertEquals(1, closed.get("players"));
            assertEquals(0, closed.get("evacuated"));
            assertEquals(1, closed.get("kicked"));
            assertEquals(false, closed.get("settle_timeout"));

            // The move of the exit, of a player gone, then lands in an instance no longer registered and fails
            far.cancel(_ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
            clock.addAndGet(EVACUATION.toNanos());
            env.tick();
            assertFalse(far.instance().isRegistered());
            release.countDown();
            assertTrue(env.tickWhile(() -> ((ManagedInstanceImpl) far).movesPending(), TEN_SECONDS),
                    "the move of the exit never completed");
        } finally {
            release.countDown();
        }
    }

    /**
     * {@code API-PLAY-006} 撤离: an exit into an instance that takes nobody, here one cancelled before, kicks
     * the player with match-unavailable as soon as its move completes with false, with one ERROR line naming
     * the exit, instead of leaving them inside until the evacuation time runs out. The player got their exit,
     * so they count as evacuated.
     */
    @Test
    void anExitIntoAnInstanceThatTakesNobodyKicksThePlayerAtOnce(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final ManagedInstance gone = instances.create(new InstanceSpec("gone", 0, FLAT, SPAWN));
        gone.cancel(_ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);
        final ListAppender<ILoggingEvent> lines = listen(Evacuation.class);
        try {
            arena.cancel(_ -> new Exit.ToInstance(gone, SPAWN));

            assertFalse(player.isOnline(), "the player was left inside until the evacuation time ran out");
            assertFalse(arena.instance().isRegistered(), "nobody was left, the startup thread unregisters at once");
            assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals(1, closed.get("players"));
            assertEquals(1, closed.get("evacuated"));
            assertEquals(0, closed.get("kicked"));
            final List<ILoggingEvent> errors = lines.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().getFormattedMessage().contains("instance=" + arena.id()),
                    errors.getFirst().getFormattedMessage());
            assertEquals("the exit " + gone.id() + " took nobody, it is CLOSED", errors.getFirst().getThrowableProxy().getMessage());
        } finally {
            stopListening(Evacuation.class, lines);
        }
    }

    /**
     * {@code API-PLAY-006} 实现: a listener of {@code AddEntityToInstanceEvent} that keeps the player out. The
     * move completes with false, the player stays where they were, and no move is recorded.
     */
    @Test
    void aMoveAListenerKeepsThePlayerOutOfCompletesWithFalse(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        arena.instance().eventNode().addListener(AddEntityToInstanceEvent.class, event -> {
            if (event.getEntity() instanceof Player) event.setCancelled(true);
        });
        final Instance world = env.createFlatInstance();
        final Player player = env.createPlayer(world, new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(player, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");

        assertFalse(moved.join(), "a player kept out of the instance counted as moved");
        assertSame(world, player.getInstance());
        assertEquals(List.of(), List.copyOf(arena.players()));
        assertEquals(List.of(), movedLines(arena));
        assertFalse(((ManagedInstanceImpl) arena).movesPending());
    }

    /**
     * {@code API-PLAY-006} 实现: a move the engine fails, here because the chunks it lands in cannot be
     * loaded. The move completes with false rather than exceptionally, the player stays where they were, and
     * one ERROR line names the plugin, the instance and the connection.
     */
    @Test
    void aMoveTheEngineFailsCompletesWithFalseAndIsWrittenOnce(Env env) {
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        arena.instance().setChunkLoader(new FailingLoader());
        arena.instance().enableAutoChunkLoad(true);
        final Instance world = env.createFlatInstance();
        final Player player = env.createPlayer(world, new Pos(0, 41, 0));
        final ListAppender<ILoggingEvent> lines = listen(ManagedInstanceImpl.class);
        try {
            final CompletableFuture<Boolean> moved = arena.move(player, OFF_THE_MAP);
            assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");

            assertFalse(moved.isCompletedExceptionally(), "the move completed exceptionally");
            assertFalse(moved.join());
            assertSame(world, player.getInstance());
            assertEquals(List.of(), movedLines(arena));
            final List<ILoggingEvent> errors = lines.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().getFormattedMessage().startsWith("Move into a managed instance failed: "
                    + "plugin_id=evacuation instance=" + arena.id() + " connection_id="
                    + player.getPlayerConnection().connectionId()), errors.getFirst().getFormattedMessage());
            assertFalse(reported.isEmpty(), "the chunk loader never failed");
        } finally {
            stopListening(ManagedInstanceImpl.class, lines);
        }
    }

    /** A chunk loader that fails every load, on a virtual thread of its own as a parallel loader does. */
    private static final class FailingLoader implements ChunkLoader {

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            throw new IllegalStateException("probe load failure");
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }

    private List<InstanceFixture.Line> movedLines(ManagedInstance managed) {
        return ledger.of(managed).stream().filter(line -> line.event().equals("play_instance_moved")).toList();
    }

    private static ListAppender<ILoggingEvent> listen(Class<?> type) {
        final ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(lines);
        return lines;
    }

    private static void stopListening(Class<?> type, ListAppender<ILoggingEvent> lines) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(lines);
    }

    /** {@code Exit.Kick}: the player is disconnected with the plugin's key and nobody is left to wait for. */
    @Test
    void aKickExitDisconnectsWithItsOwnKey(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);

        arena.cancel(_ -> new Exit.Kick("evacuation:closed"));

        assertFalse(player.isOnline());
        assertFalse(arena.instance().isRegistered(), "nobody was left, the startup thread unregisters at once");
        assertEquals(List.of("evacuation:closed"), kickKeys(disconnects));
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals(1, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /** A plugin's exit that throws: the player is kicked with match-unavailable rather than left behind. */
    @Test
    void anExitThatThrowsKicksThePlayer(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);

        arena.cancel(_ -> {
            throw new IllegalStateException("probe exit failure");
        });

        assertFalse(player.isOnline());
        assertFalse(arena.instance().isRegistered());
        assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
    }

    /** §19 「结算超时强制关闭」 with a player: the fallback exit of the spec takes them out. */
    @Test
    void theSettleTimeoutSendsThePlayersToTheFallbackExit(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", PluginApi.MATCH_DIMENSION_TYPE, 1, FLAT,
                SPAWN, Duration.ofSeconds(15), _ -> new Exit.ToInstance(lobby, SPAWN)));
        assertEquals(Duration.ofSeconds(15), arena.settleTimeout(), "the instance reports another settle timeout than its spec");
        final Player player = joinInto(env, arena, env.createConnection());
        arena.start();
        arena.end(EndCause.OBJECTIVE);

        clock.addAndGet(Duration.ofSeconds(15).toNanos());
        env.tick();
        assertEquals(State.CLOSED, arena.state());
        assertTrue(env.tickWhile(() -> arena.instance().isRegistered(), TEN_SECONDS), "never unregistered");

        assertSame(lobby.instance(), player.getInstance());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals("objective", closed.get("cause"));
        assertEquals(true, closed.get("settle_timeout"));
        assertEquals(1, closed.get("evacuated"));
    }

    /**
     * §16 「结算中按『返回大厅』与结算到点同时到」: the back button of a result asked for a move into the
     * lobby, and the instance is settled before that move ran, so the evacuation hands the player an
     * exit into the same lobby. Both moves run in turn on the player's tick thread: the first lands, the
     * second finds the player there and moves nobody. The test asks for both on the startup thread
     * before the player's next tick, the order they come in from the button and the serial part.
     */
    @Test
    void aPlayerAlreadyMovingToTheExitWhenTheInstanceSettlesLandsThereOnce(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final Player player = joinInto(env, arena, env.createConnection());
        arena.start();
        arena.end(EndCause.OBJECTIVE);
        final List<Player> handed = new CopyOnWriteArrayList<>();
        final List<Player> spawned = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.getInstance() == lobby.instance()) spawned.add(event.getPlayer());
        });

        final CompletableFuture<Boolean> button = lobby.move(player, SPAWN);
        arena.settled(leaving -> {
            handed.add(leaving);
            return new Exit.ToInstance(lobby, SPAWN);
        });
        assertEquals(List.of(player), handed, "the evacuation did not hand the player the exit");
        assertSame(arena.instance(), player.getInstance(), "a move ran before the player's tick");
        assertTrue(env.tickWhile(() -> arena.instance().isRegistered(), TEN_SECONDS), "never unregistered");

        assertTrue(button.join());
        assertSame(lobby.instance(), player.getInstance());
        assertEquals(List.of(player), spawned, "the player landed in the lobby more than once");
        assertFalse(((ManagedInstanceImpl) lobby).movesPending(), "the move of the exit is still on its way");
        assertEquals(1, ledger.of(lobby).stream().filter(line -> line.event().equals("play_instance_moved")).count());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals(1, closed.get("players"));
        assertEquals(1, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /** §16 「玩家在 move 途中断线」: the move completes with false and nobody is moved. */
    @Test
    void aPlayerWhoLeavesBeforeTheMoveRunsIsNotMoved(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final Instance world = env.createFlatInstance();
        final Player player = env.createPlayer(world, new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(player, SPAWN);
        player.kick(Component.text("left"));
        assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");

        assertFalse(moved.join());
        assertEquals(List.of(), List.copyOf(arena.players()));
        assertEquals(List.of(), ledger.of(arena).stream().filter(line -> line.event().equals("play_instance_moved")).toList());
    }

    /**
     * A plugin's exit that throws what is no {@code RuntimeException}, an {@code Error} for the first
     * player asked about and a checked exception for the second: both are kicked with match-unavailable
     * like for any other failure, the request does not throw and the instance closes.
     */
    @Test
    void anExitThatThrowsAnErrorOrACheckedExceptionKicksThePlayer(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final TestConnection first = env.createConnection();
        final Collector<DisconnectPacket> firstDisconnects = first.trackIncoming(DisconnectPacket.class);
        final Player one = joinInto(env, arena, first);
        final TestConnection second = env.createConnection();
        final Collector<DisconnectPacket> secondDisconnects = second.trackIncoming(DisconnectPacket.class);
        final Player other = joinInto(env, arena, second);
        final AtomicInteger asked = new AtomicInteger();

        arena.cancel(_ -> asked.getAndIncrement() == 0
                ? thrown(new StackOverflowError("probe exit error"))
                : thrown(new IOException("probe checked exit failure")));

        assertEquals(2, asked.get(), "the exit was not asked for every player");
        assertFalse(one.isOnline());
        assertFalse(other.isOnline());
        assertFalse(arena.instance().isRegistered());
        assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(firstDisconnects));
        assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(secondDisconnects));
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals("cancelled", closed.get("cause"));
        assertEquals(2, closed.get("players"));
        assertEquals(2, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /**
     * Throws {@code throwable} from where the compiler takes no checked exception, as code written in
     * another JVM language can.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Exit thrown(Throwable throwable) throws T {
        throw (T) throwable;
    }

    /** A plugin's exit that gives no exit at all: the player is kicked, the request still succeeds and the instance closes. */
    @Test
    void anExitThatGivesNothingKicksThePlayer(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);

        arena.cancel(_ -> nothing());

        assertFalse(player.isOnline());
        assertFalse(arena.instance().isRegistered());
        assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
        assertEquals("cancelled", ledger.last(arena, "play_instance_closed").get("cause"));
    }

    /** What a plugin's exit returns when it breaks the not-null contract of {@code Function<Player, Exit>}. */
    private static @Nullable Exit nothing() {
        return null;
    }

    /**
     * A player whose move passed the state check while the instance was open and who arrives after
     * it closed, here because the instance is cancelled from inside the move on the player's tick
     * thread: the player gets the exit like those already inside, and counts as one of them.
     */
    @Test
    void aPlayerWhoArrivesWhileTheInstanceClosesGetsTheExitToo(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        // Called on the player's tick thread before the player is added to the instance
        arena.instance().eventNode().addListener(AddEntityToInstanceEvent.class, event -> {
            if (event.getEntity() instanceof Player && arena.state() == State.OPEN)
                arena.cancel(_ -> new Exit.ToInstance(lobby, SPAWN));
        });
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(player, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone() || arena.instance().isRegistered(), TEN_SECONDS),
                "the instance was never unregistered: the player who arrived late was left inside");

        assertFalse(moved.join(), "the instance had closed by the time the player was in");
        assertSame(lobby.instance(), player.getInstance());
        assertTrue(player.isOnline());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals(1, closed.get("players"));
        assertEquals(1, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /**
     * A move on its way when the instance closes, whose player a listener keeps out: the move lands without
     * the player getting in, so they are handed no exit and are not one of the players of the closing line.
     */
    @Test
    void aPlayerKeptOutWhileTheInstanceClosesIsHandedNoExit(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final List<Player> handed = new CopyOnWriteArrayList<>();
        arena.instance().eventNode().addListener(AddEntityToInstanceEvent.class, event -> {
            if (!(event.getEntity() instanceof Player)) return;
            if (arena.state() == State.OPEN) arena.cancel(leaving -> {
                handed.add(leaving);
                return new Exit.ToInstance(lobby, SPAWN);
            });
            event.setCancelled(true);
        });
        final Instance world = env.createFlatInstance();
        final Player player = env.createPlayer(world, new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(player, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone() || arena.instance().isRegistered(), TEN_SECONDS),
                "the instance was never unregistered");

        assertFalse(moved.join());
        assertEquals(List.of(), handed, "a player who never got in was handed the exit");
        assertSame(world, player.getInstance());
        assertTrue(player.isOnline());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals(0, closed.get("players"));
        assertEquals(0, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /**
     * The closing event comes while the players are still inside and the instance registered, and a
     * forced close called from it finds the evacuation already known: it is finished at once, and the
     * plugin's exit is not asked for once the event returns.
     */
    @Test
    void aForcedCloseFromTheClosingEventFinishesTheEvacuation(Env env) {
        final InstanceRegistry registry = new InstanceRegistry(env.process(), ledger, InstanceFixture.scenes(env.process()),
                Thread.currentThread(), new InstanceRegistry.Durations(Duration.ofSeconds(30), EVACUATION), clock::get);
        InstanceFixture.registerMatchDimension(env.process());
        final PluginInstances instances = registry.forPlugin("evacuation");
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final Player player = joinInto(env, arena, env.createConnection());
        final List<Boolean> insideWhenCalled = new CopyOnWriteArrayList<>();
        arena.instance().eventNode().addListener(PlayInstanceStateEvent.class, event -> {
            if (event.to() != State.CLOSED) return;
            insideWhenCalled.add(arena.players().contains(player) && arena.instance().isRegistered());
            registry.reclaim("evacuation", EndCause.SHUTDOWN);
        });

        final List<Player> handed = new CopyOnWriteArrayList<>();

        arena.cancel(leaving -> {
            handed.add(leaving);
            return new Exit.ToInstance(lobby, SPAWN);
        });

        assertEquals(List.of(true), insideWhenCalled, "the closing event came after the players were sent away");
        assertEquals(List.of(), handed, "the exit was asked for after the forced close had finished the evacuation");
        assertFalse(arena.instance().isRegistered(), "the forced close did not finish the evacuation");
        assertFalse(player.isOnline());
        assertEquals(EndCause.CANCELLED, arena.endCause());
        final List<InstanceFixture.Line> closed = ledger.of(arena).stream()
                .filter(line -> line.event().equals("play_instance_closed")).toList();
        assertEquals(1, closed.size(), closed.toString());
        assertEquals(1, closed.getFirst().get("kicked"));
        assertEquals(0, closed.getFirst().get("evacuated"));
    }

    /**
     * A move still loading its chunks when the instance closes: the evacuation waits for it and hands
     * the player the exit once they land. The arena loads chunks on demand, which a plugin may turn on,
     * so {@code setInstance} takes the engine's asynchronous path and the move stays on its way for as
     * long as the test holds the latch of the chunks off the map.
     */
    @Test
    void theEvacuationWaitsForAMoveStillLoadingItsChunks(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
            final List<Player> handed = new CopyOnWriteArrayList<>();
            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);

            arena.cancel(leaving -> {
                handed.add(leaving);
                return new Exit.ToInstance(lobby, SPAWN);
            });
            for (int tick = 0; tick < 5; tick++) env.tick();
            assertTrue(arena.instance().isRegistered(), "unregistered while a move into it was still on its way");
            assertEquals(List.of(), handed);

            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone() || arena.instance().isRegistered(), TEN_SECONDS),
                    "the instance was never unregistered");

            assertFalse(moved.join(), "the instance had closed by the time the player was in");
            assertEquals(List.of(player), handed);
            assertSame(lobby.instance(), player.getInstance());
            assertTrue(player.isOnline());
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals(1, closed.get("players"));
            assertEquals(1, closed.get("evacuated"));
            assertEquals(0, closed.get("kicked"));
        } finally {
            release.countDown();
        }
    }

    /**
     * The evacuation time limit holds for a move still on its way: at the limit the player is kicked
     * with match-unavailable, the instance unregistered and its closing line written, and the move
     * completes with false once its chunks are in, the engine refusing to land a player in an
     * unregistered instance.
     */
    @Test
    void theEvacuationTimeLimitKicksAPlayerStillOnTheirWay(Env env) {
        final PluginInstances instances = instances(env);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            final TestConnection connection = env.createConnection();
            final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
            final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);

            arena.cancel(_ -> new Exit.Kick("evacuation:closed"));
            clock.addAndGet(EVACUATION.toNanos() - 1);
            env.tick();
            assertTrue(arena.instance().isRegistered(), "unregistered before the evacuation time was up");
            assertTrue(player.isOnline(), "kicked before the evacuation time was up");

            clock.addAndGet(1);
            env.tick();

            assertFalse(arena.instance().isRegistered(), "the move on its way held the instance past the evacuation time");
            assertFalse(player.isOnline());
            assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals(1, closed.get("players"));
            assertEquals(0, closed.get("evacuated"));
            assertEquals(1, closed.get("kicked"));

            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");
            assertFalse(moved.join());
            assertEquals(1, ledger.of(arena).stream().filter(line -> line.event().equals("play_instance_closed")).count());
        } finally {
            release.countDown();
        }
    }

    /**
     * A forced close with a move still on its way, a plugin taken back while its players are being
     * moved in: the player on their way is kicked with match-unavailable before the instance is
     * unregistered on the spot, and counts as kicked.
     */
    @Test
    void aForcedCloseKicksAPlayerStillOnTheirWay(Env env) {
        final InstanceRegistry registry = new InstanceRegistry(env.process(), ledger, InstanceFixture.scenes(env.process()),
                Thread.currentThread(), new InstanceRegistry.Durations(Duration.ofSeconds(30), EVACUATION), clock::get);
        InstanceFixture.registerMatchDimension(env.process());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = registry.forPlugin("evacuation").create(new InstanceSpec("arena", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            final TestConnection connection = env.createConnection();
            final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
            final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);

            registry.reclaim("evacuation", EndCause.SHUTDOWN);

            assertFalse(arena.instance().isRegistered());
            assertFalse(player.isOnline(), "the player on their way was left online");
            assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals("shutdown", closed.get("cause"));
            assertEquals(1, closed.get("players"));
            assertEquals(0, closed.get("evacuated"));
            assertEquals(1, closed.get("kicked"));

            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");
            assertFalse(moved.join());
        } finally {
            release.countDown();
        }
    }

    /**
     * A player the engine already put in the instance when it closes, whose move has not completed yet:
     * the snapshot of the players inside takes them and the move reports them as a late arrival, yet
     * the plugin's exit is asked for once. The instance closes from {@code PlayerSpawnEvent}, which the
     * engine calls after the player is in and before {@code setInstance} completes.
     */
    @Test
    void aPlayerWhoLandsAsTheInstanceClosesIsHandedTheExitOnce(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final List<Player> handed = new CopyOnWriteArrayList<>();
        final List<Boolean> insideWhenClosed = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.getInstance() != arena.instance() || arena.state() != State.OPEN) return;
            insideWhenClosed.add(arena.players().contains(event.getPlayer()));
            arena.cancel(leaving -> {
                handed.add(leaving);
                return new Exit.ToInstance(lobby, SPAWN);
            });
        });
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(player, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone() || arena.instance().isRegistered(), TEN_SECONDS),
                "the instance was never unregistered");

        assertEquals(List.of(true), insideWhenClosed, "the player was not inside yet when the instance closed");
        assertFalse(moved.join());
        assertEquals(List.of(player), handed, "the exit was asked for more than once");
        assertSame(lobby.instance(), player.getInstance());
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals(1, closed.get("players"));
        assertEquals(1, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
    }

    /**
     * §16: a request that closes the instance cannot come between the state check of a move and the
     * record of its player as on their way. The move into {@code arena} is held in that window on the
     * player's tick thread while the startup thread cancels, which would unregister an instance it finds
     * empty with no move on its way at once; the cancel has to wait for the monitor, finds the move on
     * its way, and the player lands, gets the exit and is counted.
     */
    @Test
    void aCloseWaitsWhileAMoveIsBetweenItsStateCheckAndItsRecord(Env env) {
        final ExecutorService startup = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("probe-startup").factory());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final InstanceRegistry registry = registry(env, onStartup(startup, Thread::currentThread));
            final ManagedInstance lobby = onStartup(startup,
                    () -> registry.forPlugin("lobby").create(new InstanceSpec("lobby", 1, FLAT, SPAWN)));
            final ManagedInstance arena = onStartup(startup, () -> registry.forPlugin("race")
                    .create(new InstanceSpec("arena", 1, InstanceFixture.stuckBeyond(1, release), SPAWN)));
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
            final List<Player> handed = new CopyOnWriteArrayList<>();
            final AtomicReference<Future<?>> closing = new AtomicReference<>();
            registry.pauseWith(window -> {
                if (window != InstanceRegistry.Window.ADMIT || closing.get() != null) return;
                final Future<?> cancel = startup.submit(() -> arena.cancel(leaving -> {
                    handed.add(leaving);
                    return new Exit.ToInstance(lobby, SPAWN);
                }));
                closing.set(cancel);
                await("the cancel to wait for the monitor", () -> someoneWaitsFor(arena) || cancel.isDone());
            });

            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);
            join(Objects.requireNonNull(closing.get(), "the move never reached its state check"), "the cancel");

            assertEquals(State.CLOSED, arena.state());
            assertTrue(arena.instance().isRegistered(), "unregistered while a move checked before the close was on its way");
            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone() || arena.instance().isRegistered(), TEN_SECONDS),
                    "the instance was never unregistered");
            assertFalse(moved.join());
            assertEquals(List.of(player), handed);
            assertSame(lobby.instance(), player.getInstance());
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals(1, closed.get("players"));
            assertEquals(1, closed.get("evacuated"));
            assertEquals(0, closed.get("kicked"));
        } finally {
            release.countDown();
            startup.shutdownNow();
        }
    }

    /**
     * §16: a move that lands while a request closing the instance is between its swap into
     * {@code CLOSED} and the publication of its evacuation waits for the monitor, then finds the
     * evacuation: the player gets the exit, the move completes with false. Without the wait the move
     * would read no evacuation, count as a plain move into a closed instance and leave the player
     * inside, past the snapshot the evacuation took.
     */
    @Test
    void aMoveThatLandsWhileTheCloseIsPublishingItsEvacuationGetsTheExit(Env env) {
        final InstanceRegistry registry = registry(env, Thread.currentThread());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance lobby = registry.forPlugin("lobby").create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
            final ManagedInstance arena = registry.forPlugin("race").create(new InstanceSpec("arena", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
            final List<Player> handed = new CopyOnWriteArrayList<>();
            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);
            final CountDownLatch inTheWindow = new CountDownLatch(1);
            registry.pauseWith(window -> {
                if (window != InstanceRegistry.Window.CLOSE) return;
                inTheWindow.countDown();
                await("the landing to wait for the monitor", () -> someoneWaitsFor(arena) || moved.isDone());
            });

            final CompletableFuture<Void> closing = onAnotherThread(() -> arena.cancel(leaving -> {
                handed.add(leaving);
                return new Exit.ToInstance(lobby, SPAWN);
            }));
            await("the cancel to reach its window", () -> inTheWindow.getCount() == 0);
            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");
            join(closing, "the cancel");

            assertFalse(moved.join(), "the move that landed in the closing instance was taken for a plain move");
            assertTrue(env.tickWhile(() -> arena.instance().isRegistered(), TEN_SECONDS), "never unregistered");
            assertEquals(List.of(player), handed);
            assertSame(lobby.instance(), player.getInstance());
            final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
            assertEquals(1, closed.get("players"));
            assertEquals(1, closed.get("evacuated"));
            assertEquals(0, closed.get("kicked"));
        } finally {
            release.countDown();
        }
    }

    /**
     * §16 「end 与停机的强制关闭同时到」: a forced close while a request closing the instance is between
     * its swap and the publication of its evacuation waits for the monitor, then finds the evacuation
     * and finishes it on the spot. Without the wait it would find the instance closed and no
     * evacuation, do nothing and leave the instance registered.
     */
    @Test
    void aForcedCloseWhileTheCloseIsPublishingItsEvacuationFinishesIt(Env env) {
        final InstanceRegistry registry = registry(env, Thread.currentThread());
        final ManagedInstance lobby = registry.forPlugin("lobby").create(new InstanceSpec("lobby", 1, FLAT, SPAWN));
        final ManagedInstance arena = registry.forPlugin("race").create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);
        final CountDownLatch inTheWindow = new CountDownLatch(1);
        final AtomicBoolean reclaimed = new AtomicBoolean();
        registry.pauseWith(window -> {
            if (window != InstanceRegistry.Window.CLOSE) return;
            inTheWindow.countDown();
            await("the forced close to wait for the monitor", () -> someoneWaitsFor(arena) || reclaimed.get());
        });

        final CompletableFuture<Void> closing = onAnotherThread(() -> arena.cancel(_ -> new Exit.ToInstance(lobby, SPAWN)));
        await("the cancel to reach its window", () -> inTheWindow.getCount() == 0);
        registry.reclaim("race", EndCause.SHUTDOWN);
        reclaimed.set(true);

        assertFalse(arena.instance().isRegistered(), "the forced close found the instance closed and no evacuation");
        assertFalse(player.isOnline());
        join(closing, "the cancel");
        assertEquals(List.of(DisconnectKeys.MATCH_UNAVAILABLE), kickKeys(disconnects));
        final List<InstanceFixture.Line> closed = ledger.of(arena).stream()
                .filter(line -> line.event().equals("play_instance_closed")).toList();
        assertEquals(1, closed.size(), closed.toString());
        assertEquals("cancelled", closed.getFirst().get("cause"));
        assertEquals(1, closed.getFirst().get("players"));
        assertEquals(0, closed.getFirst().get("evacuated"));
        assertEquals(1, closed.getFirst().get("kicked"));
    }

    /**
     * The end of an evacuation takes the players on their way before the players inside: a player whose
     * move lands between the two snapshots is in the second one and kicked, so the instance has nobody
     * online inside when it is unregistered. The forced close is held after its first snapshot while the
     * player lands, and the landing is held before it reaches the evacuation until the forced close is
     * done; the other order of the two snapshots would miss the player, and the engine would refuse to
     * unregister an instance with them inside.
     */
    @Test
    void aPlayerWhoLandsBetweenTheTwoSnapshotsOfTheEndIsKickedBeforeTheUnregistration(Env env) {
        final InstanceRegistry registry = registry(env, Thread.currentThread());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = registry.forPlugin("race").create(new InstanceSpec("arena", 1,
                    InstanceFixture.stuckBeyond(1, release), SPAWN));
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
            final CompletableFuture<Boolean> moved = onItsWay(env, arena, player);
            final CountDownLatch between = new CountDownLatch(1);
            final CountDownLatch landed = new CountDownLatch(1);
            final CountDownLatch finished = new CountDownLatch(1);
            registry.pauseWith(window -> {
                switch (window) {
                    case FINISH -> {
                        between.countDown();
                        await("the player to land", () -> landed.getCount() == 0);
                    }
                    case LANDED -> {
                        landed.countDown();
                        await("the forced close to finish", () -> finished.getCount() == 0);
                    }
                    default -> {
                    }
                }
            });

            final CompletableFuture<Void> reclaiming = onAnotherThread(() -> {
                try {
                    registry.reclaim("race", EndCause.SHUTDOWN);
                } finally {
                    finished.countDown();
                }
            });
            await("the forced close to take its first snapshot", () -> between.getCount() == 0);
            release.countDown();
            assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");
            join(reclaiming, "the forced close");

            assertFalse(arena.instance().isRegistered());
            assertFalse(player.isOnline(), "the player who landed between the two snapshots was left online");
            assertFalse(moved.join());
            final List<InstanceFixture.Line> closed = ledger.of(arena).stream()
                    .filter(line -> line.event().equals("play_instance_closed")).toList();
            assertEquals(1, closed.size(), closed.toString());
            assertEquals("shutdown", closed.getFirst().get("cause"));
            assertEquals(1, closed.getFirst().get("players"));
            assertEquals(0, closed.getFirst().get("evacuated"));
            assertEquals(1, closed.getFirst().get("kicked"));
        } finally {
            release.countDown();
        }
    }

    /** A registry like that of {@link #instances} whose startup thread is {@code startupThread}. */
    private InstanceRegistry registry(Env env, Thread startupThread) {
        InstanceFixture.registerMatchDimension(env.process());
        return new InstanceRegistry(env.process(), ledger, InstanceFixture.scenes(env.process()), startupThread,
                new InstanceRegistry.Durations(Duration.ofSeconds(30), EVACUATION), clock::get);
    }

    /** Runs {@code task} on {@code startup}, the startup thread of the test, and returns what it returned. */
    private static <T> T onStartup(ExecutorService startup, Callable<T> task) {
        final Future<T> result = startup.submit(task);
        join(result, "the task on the startup thread");
        return result.resultNow();
    }

    /** Runs {@code task} on a platform thread of its own. */
    private static CompletableFuture<Void> onAnotherThread(Runnable task) {
        return CompletableFuture.runAsync(task, command -> Thread.ofPlatform().name("probe-closer").start(command));
    }

    /** Waits at most 10 s for {@code future}, and fails with what it threw. */
    private static void join(Future<?> future, String what) {
        try {
            future.get(TEN_SECONDS.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " was interrupted", e);
        } catch (ExecutionException e) {
            throw new AssertionError(what + " threw " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError(what + " did not return within " + TEN_SECONDS, e);
        }
    }

    /** Waits on the calling thread until {@code condition} holds, at most 10 s. */
    private static void await(String what, BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TEN_SECONDS.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within " + TEN_SECONDS + ": " + what);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /**
     * Whether another thread is blocked on the monitor of {@code monitor}, which the calling thread
     * holds; false when the calling thread does not hold it.
     */
    private static boolean someoneWaitsFor(Object monitor) {
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info == null || info.getLockOwnerId() != Thread.currentThread().threadId()) continue;
            final LockInfo lock = info.getLockInfo();
            if (lock != null && lock.getIdentityHashCode() == System.identityHashCode(monitor)) return true;
        }
        return false;
    }
}
