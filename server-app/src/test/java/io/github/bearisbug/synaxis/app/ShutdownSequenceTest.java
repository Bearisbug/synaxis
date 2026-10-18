package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save phase of the server application ({@code docs/DESIGN-PROD.md} {@code API-PROD-007},
 * §11.4 {@code saving}): the player store first, then the steps registered before the start in
 * their order, out of one budget; a step that runs out of time left behind; a dead tick handing the
 * steps to the shutdown thread; and nothing but the player store when no step is registered.
 */
class ShutdownSequenceTest {

    /** The player store, as far as the sequence sees it: what it is asked, in one log with the steps. */
    private static final class Players implements ShutdownParticipant {
        private final List<String> log;
        private volatile Supplier<CompletableFuture<?>> nextSave = () -> CompletableFuture.completedFuture(null);
        private volatile ServerProcess process;

        Players(List<String> log) {
            this.log = log;
        }

        @Override
        public void onGateClosed(Duration countdown) {
            log.add("gate_closed:" + countdown.toSeconds());
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            log.add("players:" + tickAlive + (process == null ? "" : ":" + process.shutdownPhase()));
            return nextSave.get();
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
            log.add("save_ended:" + timedOut);
        }
    }

    /** Runs {@code then} after {@code millis} on a thread of its own, the future completing after it. */
    private static CompletableFuture<Void> later(long millis, Runnable then) {
        return CompletableFuture.runAsync(() -> {
            sleep(millis);
            then.run();
        }, Thread.ofVirtual()::start);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static ServerProcess process() {
        return ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
    }

    /** What {@code MinecraftServer#start} does: bind, then tick. */
    private static void start(ServerProcess process) {
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.startTicking();
    }

    @Test
    @Timeout(60)
    void theStepsRunAfterThePlayersInTheirOrderAndKickingWaitsForThem() {
        final List<String> log = new CopyOnWriteArrayList<>();
        final ServerProcess process = process();
        final Players players = new Players(log);
        players.process = process;
        players.nextSave = () -> later(200, () -> log.add("players_saved"));
        final ShutdownSequence sequence = new ShutdownSequence(process, players);
        for (String name : List.of("first", "second")) {
            sequence.addAfterPlayers(name, (_, tickAlive) -> {
                log.add(name + ":" + tickAlive + ":" + process.shutdownPhase());
                return later(300, () -> log.add(name + "_done:" + process.shutdownPhase()));
            });
        }
        process.setShutdownParticipant(sequence);
        process.scheduler().buildShutdownTask(() -> log.add("plugins_disabled"));
        start(process);

        process.stop();

        assertEquals(List.of("gate_closed:0", "players:true:SAVING", "players_saved", "first:true:SAVING",
                "first_done:SAVING", "second:true:SAVING", "second_done:SAVING", "save_ended:false",
                "plugins_disabled"), log, "a step still saving holds kicking back");
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
    }

    @Test
    void aStepGetsWhatIsLeftOfTheBudget() throws Exception {
        final List<String> log = new CopyOnWriteArrayList<>();
        final Players players = new Players(log);
        players.nextSave = () -> later(400, () -> log.add("players_saved"));
        final ShutdownSequence sequence = new ShutdownSequence(process(), players);
        final AtomicLong first = new AtomicLong();
        final AtomicLong second = new AtomicLong();
        sequence.addAfterPlayers("first", (remaining, _) -> {
            first.set(remaining.toMillis());
            return later(300, () -> { });
        });
        sequence.addAfterPlayers("second", (remaining, _) -> {
            second.set(remaining.toMillis());
            return CompletableFuture.completedFuture(null);
        });

        sequence.saveAll(Duration.ofSeconds(2), true).get(5, TimeUnit.SECONDS);

        assertTrue(first.get() <= 1_600 && first.get() >= 1_000,
                "2 s less the 400 ms the players took: " + first.get() + " ms");
        assertTrue(second.get() <= first.get() - 300 && second.get() >= 400,
                "less the 300 ms of the first step: " + second.get() + " ms after " + first.get() + " ms");
    }

    @Test
    void aStepThatDoesNotCompleteIsLeftBehindAndTheStepsAfterItDoNotRun() {
        final List<String> log = new CopyOnWriteArrayList<>();
        final Players players = new Players(log);
        final ShutdownSequence sequence = new ShutdownSequence(process(), players);
        final CompletableFuture<Void> stuck = new CompletableFuture<>();
        sequence.addAfterPlayers("stuck", (_, _) -> {
            log.add("stuck");
            return stuck;
        });
        sequence.addAfterPlayers("after", (_, _) -> {
            log.add("after");
            return CompletableFuture.completedFuture(null);
        });

        final CompletableFuture<?> saved = sequence.saveAll(Duration.ofMillis(500), true);
        sleep(800);
        final List<String> errors;
        try (LogCapture capture = LogCapture.of(ShutdownSequence.class)) {
            // What the engine does once the budget is over
            sequence.onSaveEnded(true);
            errors = capture.messagesAt(Level.ERROR);
        }
        stuck.complete(null);
        sleep(100);

        assertFalse(saved.isDone(), "the save phase is not over for the engine");
        assertEquals(List.of("players:true", "stuck", "save_ended:false"), log,
                "the players saved in time, and a step completing late starts nothing");
        assertEquals(List.of("The shutdown step after did not run, the save budget was used up"), errors);
    }

    @Test
    @Timeout(60)
    void aDeadTickHandsTheStepsToTheShutdownThreadAndKickingFollowsTheDeadline() {
        final List<String> log = new CopyOnWriteArrayList<>();
        final ServerProcess process = process();
        final Players players = new Players(log);
        players.process = process;
        final ShutdownSequence sequence = new ShutdownSequence(process, players);
        final Thread shutdownThread = Thread.currentThread();
        final AtomicLong given = new AtomicLong();
        sequence.addAfterPlayers("stuck", (remaining, tickAlive) -> {
            given.set(remaining.toMillis());
            log.add("stuck:" + tickAlive + ":" + (Thread.currentThread() == shutdownThread));
            return new CompletableFuture<>();
        });
        sequence.addAfterPlayers("after", (_, _) -> {
            log.add("after");
            return CompletableFuture.completedFuture(null);
        });
        process.setShutdownParticipant(sequence);
        process.scheduler().buildShutdownTask(() -> log.add("plugins_disabled"));
        start(process);

        final long died = System.nanoTime();
        // The watchdog's report thread, which runs the whole sequence when no shutdown was under way
        assertTrue(process.shutdownAfterWatchdogDeath());
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - died);

        assertEquals(List.of("players:false:SAVING", "stuck:false:true", "save_ended:false", "plugins_disabled"), log,
                "the step runs on the shutdown thread for a dead tick, the one after it never");
        assertTrue(given.get() > 13_000 && given.get() <= 14_000, "the 14 s of a dead process: " + given.get() + " ms");
        assertTrue(tookMillis >= 13_000 && tookMillis < 30_000, "kicking once the 14 s are over: " + tookMillis + " ms");
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
    }

    @Test
    void aDeathWhileAStepRunsForTheTickCallsItAgainOnTheShutdownThread() throws Exception {
        final List<String> log = new CopyOnWriteArrayList<>();
        final Players players = new Players(log);
        final ShutdownSequence sequence = new ShutdownSequence(process(), players);
        final Thread shutdownThread = Thread.currentThread();
        final CompletableFuture<Void> handedToTheTick = new CompletableFuture<>();
        sequence.addAfterPlayers("one", (_, tickAlive) -> {
            log.add("one:" + tickAlive + ":" + (Thread.currentThread() == shutdownThread));
            return tickAlive ? handedToTheTick : CompletableFuture.completedFuture(null);
        });
        sequence.addAfterPlayers("two", (_, tickAlive) -> {
            log.add("two:" + tickAlive + ":" + (Thread.currentThread() == shutdownThread));
            return CompletableFuture.completedFuture(null);
        });

        final CompletableFuture<?> first = sequence.saveAll(Duration.ofSeconds(30), true);
        assertFalse(first.isDone(), "the first step waits for a tick");
        // What the engine does once the watchdog judged the tick dead during the save
        final CompletableFuture<?> second = sequence.saveAll(Duration.ofSeconds(15), false);

        assertTrue(second.isDone(), "the shutdown thread ran what was left");
        handedToTheTick.complete(null);
        assertEquals(List.of("players:true", "one:true:true", "players:false", "one:false:true", "two:false:true"), log,
                "the step waiting for the tick runs again for a dead one, the next one only for a dead one, "
                        + "and the late tick starts nothing");
    }

    @Test
    void aDeathWhileThePlayersSaveRunsEveryStepOnTheShutdownThreadAfterThem() {
        final List<String> log = new CopyOnWriteArrayList<>();
        final Players players = new Players(log);
        final CompletableFuture<Void> playersOnTheTick = new CompletableFuture<>();
        players.nextSave = () -> playersOnTheTick;
        final ShutdownSequence sequence = new ShutdownSequence(process(), players);
        final Thread shutdownThread = Thread.currentThread();
        sequence.addAfterPlayers("one", (_, tickAlive) -> {
            log.add("one:" + tickAlive + ":" + (Thread.currentThread() == shutdownThread));
            return CompletableFuture.completedFuture(null);
        });

        var _ = sequence.saveAll(Duration.ofSeconds(30), true);
        players.nextSave = () -> later(200, () -> log.add("players_saved"));
        final CompletableFuture<?> dead = sequence.saveAll(Duration.ofSeconds(15), false);
        playersOnTheTick.complete(null);

        assertTrue(dead.isDone());
        assertEquals(List.of("players:true", "players:false", "players_saved", "one:false:true"), log,
                "the step waits for the players' second save and runs once, for a dead tick");
    }

    @Test
    @Timeout(60)
    void aStepIsRegisteredBeforeTheStartOnly() {
        final ServerProcess process = process();
        final ShutdownSequence sequence = new ShutdownSequence(process, new Players(new CopyOnWriteArrayList<>()));
        sequence.addAfterPlayers("early", (_, _) -> CompletableFuture.completedFuture(null));
        process.setShutdownParticipant(sequence);
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        try {
            final IllegalStateException late = assertThrows(IllegalStateException.class,
                    () -> sequence.addAfterPlayers("late", (_, _) -> CompletableFuture.completedFuture(null)));
            assertEquals("The shutdown step late is registered after the process started", late.getMessage());
        } finally {
            process.stop();
        }
        assertThrows(IllegalStateException.class,
                () -> sequence.addAfterPlayers("stopped", (_, _) -> CompletableFuture.completedFuture(null)));
    }

    @Test
    void withoutStepsTheSavePhaseIsThePlayerStoresOwn() {
        final List<String> log = new CopyOnWriteArrayList<>();
        final Players players = new Players(log);
        final CompletableFuture<Void> own = new CompletableFuture<>();
        players.nextSave = () -> own;
        final ShutdownSequence sequence = new ShutdownSequence(process(), players);

        sequence.onGateClosed(Duration.ofSeconds(60));
        assertSame(own, sequence.saveAll(Duration.ofSeconds(30), true), "the engine waits on the store's own future");
        own.complete(null);
        assertSame(own, sequence.saveAll(Duration.ofSeconds(15), false));
        sequence.onSaveEnded(true);

        assertEquals(List.of("gate_closed:60", "players:true", "players:false", "save_ended:true"), log,
                "every call passed on as it came");
    }
}
