package io.github.bearisbug.synaxis.app.instance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance.State;
import io.github.bearisbug.synaxis.plugin.instance.PlayInstanceStateEvent;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.github.bearisbug.synaxis.app.instance.InstanceFixture.FLAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every transition of §11.1 and the requests refused from every other state, the settle timeout and
 * the forced close ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, §16).
 * <p>
 * No {@code Env} and no tick: the process is built and never started, the clock is a counter the test
 * moves, and the serial part of the tick scheduler thread is the test thread processing the
 * scheduler by hand. The test thread is the startup thread, so every instance is prepared in place and
 * an empty one is unregistered on the spot.
 */
@Timeout(60)
class ManagedInstanceTransitionsTest {

    static final Function<Player, Exit> KICK =
            _ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE);
    private static final Duration SETTLE = Duration.ofSeconds(30);

    private static ServerProcess process;

    private final InstanceFixture.Ledger ledger = new InstanceFixture.Ledger();
    private final AtomicLong clock = new AtomicLong();
    private final List<PlayInstanceStateEvent> events = new CopyOnWriteArrayList<>();
    private @Nullable InstanceRegistry registry;

    @BeforeAll
    static void buildProcess() {
        process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        InstanceFixture.registerMatchDimension(process);
    }

    @AfterAll
    static void stopProcess() {
        process.stop();
    }

    private PluginInstances instances(Thread startupThread) {
        process.eventHandler().addListener(PlayInstanceStateEvent.class, event -> {
            if (event.managed().id().startsWith("transitions:")) events.add(event);
        });
        this.registry = new InstanceRegistry(process, ledger, InstanceFixture.scenes(), startupThread,
                InstanceRegistry.Durations.DEFAULT, clock::get);
        return registry.forPlugin("transitions");
    }

    private ManagedInstance open() {
        final ManagedInstance managed = instances(Thread.currentThread()).create(new InstanceSpec("round", 0, FLAT));
        assertEquals(State.OPEN, managed.state());
        return managed;
    }

    private static ManagedInstance in(State state, ManagedInstance open) {
        if (state == State.OPEN) return open;
        open.start();
        if (state == State.RUNNING) return open;
        open.end(EndCause.OBJECTIVE);
        if (state == State.ENDING) return open;
        open.settled(KICK);
        return open;
    }

    /** One tick of the tick scheduler thread's serial part, as far as the scheduler goes. */
    private static void serialPart() {
        process.scheduler().processTick();
    }

    private List<String> transitions() {
        return events.stream().map(event -> event.from() + ">" + event.to()).toList();
    }

    @Test
    void openRunEndAndSettleWriteOneLineAndCallOneEventEach() {
        final ManagedInstance round = open();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        round.start();
        assertEquals(State.RUNNING, round.state());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        round.end(EndCause.TIME_LIMIT);
        assertEquals(State.ENDING, round.state());
        assertEquals(EndCause.TIME_LIMIT, round.endCause());
        round.settled(KICK);

        assertEquals(State.CLOSED, round.state());
        assertEquals(EndCause.TIME_LIMIT, round.endCause(), "settled keeps the cause of end");
        assertFalse(round.instance().isRegistered(), "an empty instance settled on the startup thread is unregistered at once");
        assertEquals(List.of("PREPARING>OPEN", "OPEN>RUNNING", "RUNNING>ENDING", "ENDING>CLOSED"), transitions());
        assertEquals(List.of("play_instance_created", "play_instance_opened", "play_instance_started",
                "play_instance_ended", "play_instance_closed"), ledger.events(round));
        assertEquals(0, ledger.last(round, "play_instance_started").get("players"));
        final InstanceFixture.Line ended = ledger.last(round, "play_instance_ended");
        assertEquals("time_limit", ended.get("cause"));
        assertEquals(5_000L, ended.get("duration_ms"));
        final InstanceFixture.Line closed = ledger.last(round, "play_instance_closed");
        assertEquals("time_limit", closed.get("cause"));
        assertEquals(0, closed.get("players"));
        assertEquals(0, closed.get("evacuated"));
        assertEquals(0, closed.get("kicked"));
        assertEquals(false, closed.get("settle_timeout"));
    }

    @Test
    void cancelClosesAnOpenInstance() {
        final ManagedInstance round = open();

        round.cancel(KICK);

        assertEquals(State.CLOSED, round.state());
        assertEquals(EndCause.CANCELLED, round.endCause());
        assertFalse(round.instance().isRegistered());
        assertEquals("cancelled", ledger.last(round, "play_instance_closed").get("cause"));
        assertEquals(List.of("PREPARING>OPEN", "OPEN>CLOSED"), transitions());
    }

    /** §19 「非法转移」 and the rest of the table: a refused request changes nothing and writes nothing. */
    @ParameterizedTest
    @EnumSource(value = State.class, names = {"OPEN", "RUNNING", "ENDING", "CLOSED"})
    void everyRequestFromAStateItDoesNotLeaveIsRefused(State state) {
        final ManagedInstance round = in(state, open());
        final List<String> lines = ledger.events(round);
        final List<String> calledEvents = transitions();
        final List<Consumer<ManagedInstance>> refused = switch (state) {
            case OPEN -> List.of(managed -> managed.end(EndCause.OBJECTIVE), managed -> managed.settled(KICK));
            case RUNNING -> List.of(ManagedInstance::start, managed -> managed.cancel(KICK), managed -> managed.settled(KICK));
            case ENDING -> List.of(ManagedInstance::start, managed -> managed.cancel(KICK), managed -> managed.end(EndCause.EMPTY));
            case CLOSED -> List.of(ManagedInstance::start, managed -> managed.cancel(KICK),
                    managed -> managed.end(EndCause.ERROR), managed -> managed.settled(KICK));
            case PREPARING -> fail("not a parameter");
        };

        for (Consumer<ManagedInstance> request : refused) {
            assertThrows(IllegalStateException.class, () -> request.accept(round));
            assertEquals(state, round.state());
        }
        assertEquals(lines, ledger.events(round));
        assertEquals(calledEvents, transitions());
    }

    /** {@code end} takes the four causes of a plugin only; the host's own are refused before the state is looked at. */
    @ParameterizedTest
    @EnumSource(value = EndCause.class, names = {"CANCELLED", "PREPARE_FAILED", "PLUGIN_DISABLED", "SHUTDOWN"})
    void endRefusesTheCausesOfTheHost(EndCause cause) {
        final ManagedInstance round = in(State.RUNNING, open());

        assertThrows(IllegalArgumentException.class, () -> round.end(cause));

        assertEquals(State.RUNNING, round.state());
        assertNull(round.endCause());
    }

    /**
     * A preparing instance refuses every request and every move. It only exists while {@code create} is
     * still running, so the startup thread here is another thread, stuck in a generator, and the test
     * thread finds the instance through {@code owned()}.
     */
    @Test
    void aPreparingInstanceRefusesEveryRequest() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<PluginInstances> instances = new AtomicReference<>();
        final AtomicReference<ManagedInstance> created = new AtomicReference<>();
        final Thread startup = Thread.ofPlatform().name("probe-startup").unstarted(() ->
                created.set(instances.get().create(new InstanceSpec("round", 0, InstanceFixture.stuckUntil(release)))));
        instances.set(instances(startup));
        try {
            startup.start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (instances.get().owned().isEmpty()) {
                if (System.nanoTime() - deadline > 0) fail("create never got as far as preparing");
                Thread.onSpinWait();
            }
            final ManagedInstance preparing = instances.get().owned().getFirst();

            assertEquals(State.PREPARING, preparing.state());
            assertThrows(IllegalStateException.class, preparing::start);
            assertThrows(IllegalStateException.class, () -> preparing.cancel(KICK));
            assertThrows(IllegalStateException.class, () -> preparing.end(EndCause.OBJECTIVE));
            assertThrows(IllegalStateException.class, () -> preparing.settled(KICK));
            assertFalse(preparing.instance().isRegistered());
            assertEquals(State.PREPARING, preparing.state());
        } finally {
            release.countDown();
        }
        assertTrue(startup.join(Duration.ofSeconds(10)), "create did not return once the generator was released");
        assertEquals(State.OPEN, created.get().state());
    }

    /** §19 「结算超时强制关闭」: closed at exactly the settle timeout, by the host, with a WARN. */
    @Test
    void anInstanceNotSettledInTimeIsClosedByTheHost() {
        final ManagedInstance round = in(State.ENDING, open());
        final Logger logger = (Logger) LoggerFactory.getLogger(ManagedInstanceImpl.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            clock.addAndGet(SETTLE.toNanos() - 1);
            serialPart();
            assertEquals(State.ENDING, round.state(), "closed before the settle timeout");

            clock.addAndGet(1);
            serialPart();
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(State.CLOSED, round.state());
        assertEquals(EndCause.OBJECTIVE, round.endCause(), "the settle timeout keeps the cause of end");
        assertFalse(round.instance().isRegistered());
        final InstanceFixture.Line closed = ledger.last(round, "play_instance_closed");
        assertEquals("objective", closed.get("cause"));
        assertEquals(true, closed.get("settle_timeout"));
        assertEquals(1, appender.list.stream().filter(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains(round.id())).count(), appender.list.toString());
        assertThrows(IllegalStateException.class, () -> round.settled(KICK));
    }

    /** §16 「settled 与结算超时同时到」: the one that comes first wins, the other does nothing. */
    @Test
    void aSettledInstanceIsNotClosedAgainByTheTimeout() {
        final ManagedInstance round = in(State.ENDING, open());
        round.settled(KICK);

        clock.addAndGet(SETTLE.toNanos());
        serialPart();
        serialPart();

        assertEquals(1, ledger.events(round).stream().filter("play_instance_closed"::equals).count());
        assertEquals(false, ledger.last(round, "play_instance_closed").get("settle_timeout"));
        assertEquals(List.of("PREPARING>OPEN", "OPEN>RUNNING", "RUNNING>ENDING", "ENDING>CLOSED"), transitions());
    }

    /** §11.1 forced close and §16 「end 与停机的强制关闭同时到」. */
    @ParameterizedTest
    @EnumSource(value = State.class, names = {"OPEN", "RUNNING", "ENDING"})
    void aForcedCloseUnregistersAndRefusesWhatComesAfter(State state) {
        final ManagedInstance round = in(state, open());
        final InstanceRegistry reclaiming = Objects.requireNonNull(registry);

        reclaiming.reclaim("transitions", EndCause.SHUTDOWN);
        reclaiming.reclaim("transitions", EndCause.SHUTDOWN);

        assertEquals(State.CLOSED, round.state());
        assertEquals(EndCause.SHUTDOWN, round.endCause());
        assertFalse(round.instance().isRegistered());
        assertEquals(1, ledger.events(round).stream().filter("play_instance_closed"::equals).count());
        assertEquals("shutdown", ledger.last(round, "play_instance_closed").get("cause"));
        assertEquals(state + ">CLOSED", transitions().getLast());
        assertThrows(IllegalStateException.class, () -> round.end(EndCause.OBJECTIVE));
    }

    /** The closing event is called after the swap and before the evacuation, so the instance is still registered then. */
    @Test
    void theClosingEventComesBeforeTheUnregistration() {
        final ManagedInstance round = open();
        final List<Boolean> registeredWhenCalled = new CopyOnWriteArrayList<>();
        round.instance().eventNode().addListener(PlayInstanceStateEvent.class, event -> {
            if (event.to() == State.CLOSED) registeredWhenCalled.add(event.getInstance().isRegistered());
        });

        round.cancel(KICK);

        assertEquals(List.of(true), registeredWhenCalled);
        assertFalse(round.instance().isRegistered());
    }

    /**
     * §11.1 {@code PREPARING → OPEN} against a forced close: the instance is registered before the swap, so
     * a forced close that comes between the two finds it preparing and drops it, the swap fails and the
     * instance is unregistered again. The registration holds no lock, so the forced close comes in on the
     * same thread, from the window between the two steps, in the order another thread could take.
     */
    @Test
    void aForcedCloseBetweenTheRegistrationAndTheSwapLeavesNothingRegistered() {
        final PluginInstances instances = instances(Thread.currentThread());
        final InstanceRegistry reclaiming = Objects.requireNonNull(registry);
        reclaiming.pauseWith(window -> {
            if (window == InstanceRegistry.Window.OPEN) reclaiming.reclaim("transitions", EndCause.SHUTDOWN);
        });

        final ManagedInstance round = instances.create(new InstanceSpec("round", 0, FLAT));

        assertEquals(State.CLOSED, round.state());
        assertEquals(EndCause.SHUTDOWN, round.endCause());
        assertFalse(round.instance().isRegistered(), "a closed instance was left registered");
        assertFalse(process.instance().getInstances().contains(round.instance()));
        assertEquals(List.of("play_instance_created", "play_instance_closed"), ledger.events(round));
        assertEquals(List.of("PREPARING>CLOSED"), transitions());
    }

    /** §11.1 {@code plugin.tornDown}: a plugin taken back creates no more instances, which nothing would take back. */
    @Test
    void aPluginTakenBackCreatesNoMoreInstances() {
        final ManagedInstance round = open();
        final PluginInstances instances = Objects.requireNonNull(registry).forPlugin("transitions");

        registry.reclaim("transitions", EndCause.PLUGIN_DISABLED);

        assertEquals(State.CLOSED, round.state());
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> instances.create(new InstanceSpec("round", 0, FLAT)));
        assertTrue(refused.getMessage().contains("transitions"), refused.getMessage());
        assertEquals(List.of(), instances.owned());
    }

    /**
     * Once the instance is open the host no longer keeps what the plugin's generator throws: a chunk off
     * the map loaded later fails the way it would without the host, through the exception handler of
     * the process, and the instance stays open.
     */
    @Test
    void aGeneratorThatThrowsOnceTheInstanceIsOpenReachesTheExceptionHandler() {
        final IllegalStateException failure = new IllegalStateException("probe generator failure off the map");
        final Generator throwsOffTheMap = unit -> {
            if (unit.absoluteStart().chunkX() != 0 || unit.absoluteStart().chunkZ() != 0) throw failure;
            FLAT.generate(unit);
        };
        final ManagedInstance round = instances(Thread.currentThread()).create(new InstanceSpec("round", 0, throwsOffTheMap));
        assertEquals(State.OPEN, round.state());
        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(handled::add);
        try {
            var _ = round.instance().loadChunk(2, 2).join();
        } finally {
            process.exception().setExceptionHandler(null);
        }

        assertEquals(List.of(failure), handled);
        assertEquals(State.OPEN, round.state(), "a chunk off the map does not close an open instance");
    }
}
