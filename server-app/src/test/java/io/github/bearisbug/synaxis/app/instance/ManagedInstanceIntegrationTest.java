package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance.State;
import io.github.bearisbug.synaxis.plugin.instance.PlayInstanceStateEvent;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLAT;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLOOR;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.SPAWN;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.computedSkyLight;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code create} on the startup thread, which the test thread of an {@link Env} is: the instance is
 * prepared in place and comes back open ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, §11.1, §19).
 * The path of the tick scheduler thread is in {@link ManagedInstanceTickThreadTest}: {@link Env#tick}
 * runs on the test thread and never reaches it.
 */
@EnvTest
class ManagedInstanceIntegrationTest {

    private static final String PLUGIN = "probe";
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private final InstanceFixture.Ledger ledger = new InstanceFixture.Ledger();
    private final AtomicLong clock = new AtomicLong();

    private PluginInstances instances(Env env, InstanceRegistry.Durations durations) {
        InstanceFixture.registerMatchDimension(env.process());
        return new InstanceRegistry(env.process(), ledger, InstanceFixture.scenes(env.process()), Thread.currentThread(),
                durations, clock::get).forPlugin(PLUGIN);
    }

    private PluginInstances instances(Env env) {
        return instances(env, InstanceRegistry.Durations.DEFAULT);
    }

    /** §19 「tick 调度线程上建实例不占 tick」, its startup thread half; MatchIntegrationTest#theMapIsLitBeforeTheMatchRuns. */
    @Test
    void onTheStartupThreadCreateComesBackOpenWithTheWholeMapLit(Env env) {
        final PluginInstances instances = instances(env);
        final List<PlayInstanceStateEvent> events = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(PlayInstanceStateEvent.class, events::add);
        clock.set(1_000_000_000L);

        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 2, FLAT, SPAWN));

        assertEquals(State.OPEN, arena.state());
        final InstanceContainer instance = arena.instance();
        assertTrue(instance.isRegistered(), "an open instance is registered");
        assertTrue(env.process().instance().getInstances().contains(instance));
        assertEquals(25, instance.getChunks().size(), "a radius of 2 is 5 x 5 chunks");
        assertFalse(instance.hasEnabledAutoChunkLoad(), "nothing outside the map may be loaded on demand");
        for (int x = -32; x < 48; x += 7) {
            for (int z = -32; z < 48; z += 5) {
                assertEquals(15, computedSkyLight(instance, x, FLOOR, z),
                        "column " + x + "," + z + ": the air above the map is not lit by the sky");
                assertEquals(0, computedSkyLight(instance, x, FLOOR - 2, z),
                        "column " + x + "," + z + ": sky light leaked into the stone");
            }
        }
        assertEquals(List.of(arena), instances.owned());
        assertEquals(List.of("play_instance_created", "play_instance_opened"), ledger.events(arena));
        final InstanceFixture.Line created = ledger.last(arena, "play_instance_created");
        assertEquals(Map.of("kind_id", "probe:arena", "instance_id", arena.instanceId().toString(), "chunk_radius", 2),
                created.attributes());
        assertEquals(0L, ledger.last(arena, "play_instance_opened").get("prepare_ms"),
                "the injected clock did not move while the instance prepared");
        assertEquals(1, events.size(), events.toString());
        assertSame(arena, events.getFirst().managed());
        assertEquals(State.PREPARING, events.getFirst().from());
        assertEquals(State.OPEN, events.getFirst().to());
    }

    /** §19 「超出半径上限」: the spec cannot be built, so create never sees it. */
    @Test
    void aRadiusOf17NeverReachesCreate(Env env) {
        final PluginInstances instances = instances(env);
        final int registered = env.process().instance().getInstances().size();

        assertThrows(IllegalArgumentException.class, () -> instances.create(new InstanceSpec("arena", 17, FLAT, SPAWN)));

        assertEquals(List.of(), instances.owned());
        assertEquals(List.of(), ledger.lines());
        assertEquals(registered, env.process().instance().getInstances().size());
    }

    /** §11.1 {@code [*] → PREPARING}: the dimension type has to be one registered before the port was bound. */
    @Test
    void aDimensionTypeNobodyRegisteredIsRefusedBeforeAnythingIsBuilt(Env env) {
        final PluginInstances instances = instances(env);

        for (String dimensionType : List.of("probe:nowhere", "Not A Key")) {
            final InstanceSpec spec = new InstanceSpec("arena", dimensionType, 0, FLAT, SPAWN, Duration.ofSeconds(30),
                    _ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> instances.create(spec));
            assertTrue(refused.getMessage().contains(dimensionType), refused.getMessage());
        }
        assertEquals(List.of(), instances.owned());
        assertEquals(List.of(), ledger.lines());
    }

    /** §11.1 {@code [*] → PREPARING}: neither the startup thread nor the tick scheduler thread. */
    @Test
    void createOnAnyOtherThreadIsRefused(Env env) throws InterruptedException {
        final PluginInstances instances = instances(env);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();

        final Thread other = Thread.ofPlatform().name("probe-other").start(() -> {
            try {
                instances.create(new InstanceSpec("arena", 0, FLAT, SPAWN));
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });
        assertTrue(other.join(TEN_SECONDS), "the other thread is still in create");

        final IllegalStateException refused = assertInstanceOf(IllegalStateException.class, thrown.get());
        assertTrue(refused.getMessage().contains("probe-other"), refused.getMessage());
        assertEquals(List.of(), instances.owned());
        assertEquals(List.of(), ledger.lines());
    }

    /** §16 「move 进一个刚转 ENDING 的实例」, after a move into the same instance while it was open. */
    @Test
    void aMoveIntoAnEndingInstanceCompletesWithFalse(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1, FLAT, SPAWN));
        final Instance lobby = env.createFlatInstance();
        final Player first = env.createPlayer(lobby, new Pos(0, 41, 0));

        final CompletableFuture<Boolean> moved = arena.move(first, SPAWN);
        assertTrue(env.tickWhile(() -> !moved.isDone(), TEN_SECONDS), "the move never completed");

        assertTrue(moved.join());
        assertSame(arena.instance(), first.getInstance());
        assertEquals(Set.of(first), arena.players());
        final InstanceFixture.Line line = ledger.last(arena, "play_instance_moved");
        assertEquals(first.getPlayerConnection().connectionId(), line.get("connection_id"));
        assertEquals(lobby.getUuid().toString(), line.get("from_instance_id"));

        arena.start();
        arena.end(EndCause.OBJECTIVE);
        final Player second = env.createPlayer(lobby, new Pos(0, 41, 0));
        final CompletableFuture<Boolean> late = arena.move(second, SPAWN);

        assertTrue(late.isDone(), "a move into an ending instance is refused at once");
        assertFalse(late.join());
        env.tick();
        assertSame(lobby, second.getInstance());
        assertEquals(1, ledger.of(arena).stream().filter(entry -> entry.event().equals("play_instance_moved")).count());
    }

    /** {@code API-PLAY-006} {@code PlayInstanceStateEvent}: forInstance hangs its listeners on the instance node. */
    @Test
    void theStateEventReachesTheInstanceNodeAndTheGlobalOne(Env env) {
        final PluginInstances instances = instances(env);
        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 0, FLAT, SPAWN));
        final List<PlayInstanceStateEvent> scoped = new CopyOnWriteArrayList<>();
        final List<PlayInstanceStateEvent> global = new CopyOnWriteArrayList<>();
        arena.instance().eventNode().addListener(PlayInstanceStateEvent.class, scoped::add);
        env.process().eventHandler().addListener(PlayInstanceStateEvent.class, global::add);

        arena.start();

        for (List<PlayInstanceStateEvent> received : List.of(scoped, global)) {
            assertEquals(1, received.size(), received.toString());
            assertSame(arena, received.getFirst().managed());
            assertSame(arena.instance(), received.getFirst().getInstance());
            assertEquals(State.OPEN, received.getFirst().from());
            assertEquals(State.RUNNING, received.getFirst().to());
        }
    }

    /** {@code InstanceSpec#name}: the dimension name is {@code <plugin_id>:<name>_<n>}, a number per name. */
    @Test
    void everyInstanceHasADimensionNameOfItsOwn(Env env) {
        final PluginInstances instances = instances(env);

        final ManagedInstance first = instances.create(new InstanceSpec("arena", 0, FLAT, SPAWN));
        final ManagedInstance second = instances.create(new InstanceSpec("arena", 0, FLAT, SPAWN));
        final ManagedInstance lobby = instances.create(new InstanceSpec("lobby", 0, FLAT, SPAWN));

        assertEquals(List.of("probe:arena_1", "probe:arena_2", "probe:lobby_1"),
                List.of(first.id(), second.id(), lobby.id()));
        for (ManagedInstance managed : List.of(first, second, lobby))
            assertEquals(managed.id(), managed.instance().getDimensionName());
        assertNotSame(first.instanceId(), second.instanceId());
        assertEquals(List.of(first, second, lobby), instances.owned());
    }

    /** §11.1 {@code PREPARING → CLOSED} on the startup thread: the engine alone would count the chunk as loaded. */
    @Test
    void aGeneratorThatThrowsClosesTheInstanceWithoutRegisteringIt(Env env) {
        final PluginInstances instances = instances(env);

        final ManagedInstance arena = instances.create(new InstanceSpec("arena", 1,
                _ -> {
                    throw new IllegalStateException("probe generator failure");
                }, SPAWN));

        assertEquals(State.CLOSED, arena.state());
        assertEquals(EndCause.PREPARE_FAILED, arena.endCause());
        assertFalse(arena.instance().isRegistered());
        assertEquals(List.of(), instances.owned());
        assertEquals(List.of("play_instance_created", "play_instance_closed"), ledger.events(arena));
        final InstanceFixture.Line closed = ledger.last(arena, "play_instance_closed");
        assertEquals("prepare_failed", closed.get("cause"));
        assertEquals(0, closed.get("players"));
        assertEquals(false, closed.get("settle_timeout"));
    }

    /** §11.1 {@code PREPARING → CLOSED} on the startup thread: the wait for the chunks is bounded by the budget. */
    @Test
    void chunksNotLoadedWithinTheBudgetCloseTheInstance(Env env) {
        final PluginInstances instances = instances(env,
                new InstanceRegistry.Durations(Duration.ofMillis(200), Duration.ofSeconds(5)));
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = instances.create(new InstanceSpec("arena", 0,
                    InstanceFixture.stuckUntil(release), SPAWN));

            assertEquals(State.CLOSED, arena.state());
            assertEquals(EndCause.PREPARE_FAILED, arena.endCause());
            assertFalse(arena.instance().isRegistered());
            assertEquals("prepare_failed", ledger.last(arena, "play_instance_closed").get("cause"));
        } finally {
            release.countDown();
        }
    }
}
