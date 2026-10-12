package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance.State;
import io.github.bearisbug.synaxis.plugin.instance.PlayInstanceStateEvent;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLAT;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLOOR;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.SPAWN;
import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.computedSkyLight;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code create} on the tick scheduler thread ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, §11.1,
 * §15, §19): the instance comes back preparing, is generated and lit outside the tick and turns open in
 * a later serial part.
 * <p>
 * No {@code Env}: {@code Env#tick} runs the ticker on the test thread, which is not the tick scheduler
 * thread, so this class starts a real {@link TickSchedulerThread} and its tick threads the way
 * ShardWorldAssemblyTest#startTicking does, and stops the process when it is done. Every wait is
 * bounded at 10 s; a preparing instance is held there by a generator waiting on a latch, not by timing.
 * The clock of the registry is the system's plus an offset the budget test moves.
 */
@Timeout(60)
class ManagedInstanceTickThreadTest {

    private static final long WAIT_SECONDS = 10;

    private static ServerProcess process;
    private static InstanceRegistry registry;
    private static final AtomicLong clockOffset = new AtomicLong();
    private static final InstanceFixture.Ledger ledger = new InstanceFixture.Ledger();
    /** The transitions seen so far, by instance id and target state, with the thread that called the event. */
    private static final Map<String, CompletableFuture<String>> transitions = new ConcurrentHashMap<>();
    private static final AtomicReference<Throwable> caught = new AtomicReference<>();

    @BeforeAll
    static void startTicking() {
        process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                // The multi-threaded CI rounds set these, as EnvTestExt passes them on (REQ-PROD-019, REQ-PROD-020)
                .dispatcherThreads(ServerConfig.fromSystemProperties().dispatcherThreads())
                .threadProvider(ServerConfig.fromSystemProperties().threadProvider())
                .build());
        process.exception().setExceptionHandler(throwable -> caught.compareAndSet(null, throwable));
        InstanceFixture.registerMatchDimension(process);
        process.eventHandler().addListener(PlayInstanceStateEvent.class, event ->
                transition(event.managed(), event.to()).complete(Thread.currentThread().getName()));
        // The startup thread is one that never calls create: the test thread counts as any other thread
        registry = new InstanceRegistry(process, ledger, InstanceFixture.scenes(process), Thread.ofPlatform().unstarted(() -> {
        }), InstanceRegistry.Durations.DEFAULT, () -> System.nanoTime() + clockOffset.get());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
    }

    @AfterAll
    static void stopProcess() {
        process.stop();
    }

    @AfterEach
    void nothingReachedTheExceptionHandler() {
        assertNull(caught.getAndSet(null), "the process handed an exception to its handler");
    }

    private static CompletableFuture<String> transition(ManagedInstance managed, State to) {
        return transitions.computeIfAbsent(managed.id() + ">" + to, _ -> new CompletableFuture<>());
    }

    /** Waits for {@code managed} to turn {@code to}, and says on which thread it did. */
    private static String awaitTransition(ManagedInstance managed, State to)
            throws InterruptedException, ExecutionException {
        try {
            return transition(managed, to).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(managed.id() + " did not turn " + to + " within " + WAIT_SECONDS + " s, it is "
                    + managed.state() + "; ledger " + ledger.of(managed), e);
        }
    }

    /** Runs {@code task} in the next serial part of the tick scheduler thread and waits for what it returns. */
    private static <T> T onTheTickScheduler(Supplier<T> task) throws InterruptedException, ExecutionException {
        final CompletableFuture<T> result = new CompletableFuture<>();
        process.scheduler().scheduleNextTick(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        try {
            return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the tick scheduler did not run the task within " + WAIT_SECONDS + " s", e);
        }
    }

    /** Lets two serial parts go by: the check that would register a prepared instance runs once in each. */
    private static void twoSerialParts() throws InterruptedException, ExecutionException {
        onTheTickScheduler(() -> null);
        onTheTickScheduler(() -> null);
    }

    private record Created(ManagedInstance managed, State state, boolean registered, long nanos) {
    }

    private static Created create(PluginInstances instances, InstanceSpec spec)
            throws InterruptedException, ExecutionException {
        return onTheTickScheduler(() -> {
            final long start = System.nanoTime();
            final ManagedInstance managed = instances.create(spec);
            final long nanos = System.nanoTime() - start;
            return new Created(managed, managed.state(), managed.instance().isRegistered(), nanos);
        });
    }

    /** §19 「tick 调度线程上建实例不占 tick」 and the two figures of §15. */
    @Test
    void onTheTickSchedulerCreateComesBackPreparingAndOpensInALaterSerialPart()
            throws InterruptedException, ExecutionException {
        final Created created = create(registry.forPlugin("tick"), new InstanceSpec("arena", 2, FLAT, SPAWN));
        final ManagedInstance arena = created.managed();

        assertEquals(State.PREPARING, created.state(), "create waited for the chunks on the tick scheduler thread");
        assertFalse(created.registered(), "registered before its chunks were generated and lit");
        assertEquals(TickSchedulerThread.THREAD_NAME_TICK_SCHEDULER, awaitTransition(arena, State.OPEN),
                "registered outside the serial part");
        // The registration measures itself up to the state event's return, which is after the event above
        onTheTickScheduler(() -> null);

        assertEquals(State.OPEN, arena.state());
        final InstanceContainer instance = arena.instance();
        assertTrue(instance.isRegistered());
        assertTrue(process.instance().getInstances().contains(instance));
        assertEquals(25, instance.getChunks().size());
        assertFalse(instance.hasEnabledAutoChunkLoad());
        for (int x = -32; x < 48; x += 7) {
            for (int z = -32; z < 48; z += 5) {
                assertEquals(15, computedSkyLight(instance, x, FLOOR, z),
                        "column " + x + "," + z + ": the air above the map is not lit by the sky");
                assertEquals(0, computedSkyLight(instance, x, FLOOR - 2, z),
                        "column " + x + "," + z + ": sky light leaked into the stone");
            }
        }
        assertEquals(List.of("play_instance_created", "play_instance_opened"), ledger.events(arena));
        final long prepareMs = ((Number) ledger.last(arena, "play_instance_opened").get("prepare_ms")).longValue();
        final long registerNanos = ((ManagedInstanceImpl) arena).registerNanos();
        assertTrue(registerNanos >= 0, "the registration did not measure itself");
        System.out.printf("§15 readings (chunkRadius=2, 25 chunks): create on the tick scheduler thread %d us, "
                        + "registration in the serial part %d us, prepare_ms %d%n",
                TimeUnit.NANOSECONDS.toMicros(created.nanos()), TimeUnit.NANOSECONDS.toMicros(registerNanos), prepareMs);
    }

    /** §19 「准备中停机」: dropped, closed with cause shutdown, and still not registered once the chunks arrive. */
    @Test
    void theShutdownDropsAPreparingInstanceAndItIsNeverRegistered() throws InterruptedException, ExecutionException {
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final ManagedInstance arena = create(registry.forPlugin("stuck"),
                    new InstanceSpec("arena", 2, InstanceFixture.stuckUntil(release), SPAWN)).managed();
            assertEquals(State.PREPARING, arena.state());

            // The shutdown task of the host runs on the tick scheduler thread, between two ticks
            onTheTickScheduler(() -> {
                registry.reclaim("stuck", EndCause.SHUTDOWN);
                return null;
            });

            assertEquals(State.CLOSED, arena.state());
            assertEquals(EndCause.SHUTDOWN, arena.endCause());
            assertEquals(List.of("play_instance_created", "play_instance_closed"), ledger.events(arena));
            assertEquals("shutdown", ledger.last(arena, "play_instance_closed").get("cause"));

            release.countDown();
            ((ManagedInstanceImpl) arena).preparation().get(WAIT_SECONDS, TimeUnit.SECONDS);
            twoSerialParts();

            assertFalse(arena.instance().isRegistered(), "registered after the shutdown dropped it");
            assertFalse(process.instance().getInstances().contains(arena.instance()));
            assertEquals(List.of("play_instance_created", "play_instance_closed"), ledger.events(arena));
        } catch (TimeoutException e) {
            throw new AssertionError("the released generator did not finish within " + WAIT_SECONDS + " s", e);
        } finally {
            release.countDown();
        }
    }

    /** §11.1 {@code PREPARING → CLOSED} outside the tick: 30 s on the registry's clock. */
    @Test
    void aPreparationOverTheBudgetClosesTheInstance() throws InterruptedException, ExecutionException {
        final CountDownLatch release = new CountDownLatch(1);
        final long budget = InstanceRegistry.Durations.DEFAULT.prepareBudget().toNanos();
        try {
            final ManagedInstance arena = create(registry.forPlugin("slow"),
                    new InstanceSpec("arena", 0, InstanceFixture.stuckUntil(release), SPAWN)).managed();
            twoSerialParts();
            assertEquals(State.PREPARING, arena.state(), "closed before the budget was spent");

            clockOffset.addAndGet(budget);
            assertEquals(TickSchedulerThread.THREAD_NAME_TICK_SCHEDULER, awaitTransition(arena, State.CLOSED));

            assertEquals(EndCause.PREPARE_FAILED, arena.endCause());
            assertEquals("prepare_failed", ledger.last(arena, "play_instance_closed").get("cause"));
            release.countDown();
            ((ManagedInstanceImpl) arena).preparation().get(WAIT_SECONDS, TimeUnit.SECONDS);
            twoSerialParts();
            assertFalse(arena.instance().isRegistered());
        } catch (TimeoutException e) {
            throw new AssertionError("the released generator did not finish within " + WAIT_SECONDS + " s", e);
        } finally {
            clockOffset.addAndGet(-budget);
            release.countDown();
        }
    }

    /** §11.1 {@code PREPARING → CLOSED} outside the tick: the generator threw. */
    @Test
    void aGeneratorThatThrowsOutsideTheTickClosesTheInstance() throws InterruptedException, ExecutionException {
        final ManagedInstance arena = create(registry.forPlugin("broken"), new InstanceSpec("arena", 1, _ -> {
            throw new IllegalStateException("probe generator failure");
        }, SPAWN)).managed();

        assertEquals(TickSchedulerThread.THREAD_NAME_TICK_SCHEDULER, awaitTransition(arena, State.CLOSED));

        assertEquals(EndCause.PREPARE_FAILED, arena.endCause());
        assertFalse(arena.instance().isRegistered());
        assertEquals(List.of("play_instance_created", "play_instance_closed"), ledger.events(arena));
    }
}
