package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance.State;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

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
        final ManagedInstance gone = instances.create(new InstanceSpec("gone", 0, FLAT, SPAWN));
        gone.cancel(_ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
        final TestConnection connection = env.createConnection();
        final Collector<DisconnectPacket> disconnects = connection.trackIncoming(DisconnectPacket.class);
        final Player player = joinInto(env, arena, connection);
        arena.start();
        arena.end(EndCause.TIME_LIMIT);

        // The exit is an instance that refuses the move, so the player stays where they are
        arena.settled(_ -> new Exit.ToInstance(gone, SPAWN));
        for (int tick = 0; tick < 5; tick++) env.tick();
        clock.addAndGet(EVACUATION.toNanos() - 1);
        env.tick();
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
}
