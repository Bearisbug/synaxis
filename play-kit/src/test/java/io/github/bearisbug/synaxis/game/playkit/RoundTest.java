package io.github.bearisbug.synaxis.game.playkit;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.event.SubstituteLoggingEvent;
import org.slf4j.helpers.SubstituteLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Round} over a stand-in for the host's managed instance and a hand driven clock
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}, §19 "Round 回调抛异常"): the phases it moves an
 * instance through, and the guard around every callback.
 */
class RoundTest {

    private static final Duration LENGTH = Duration.ofSeconds(10);
    private static final Duration RESULT = Duration.ofSeconds(3);

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final FakeArena arena = new FakeArena();
    private final Queue<SubstituteLoggingEvent> logged = new ConcurrentLinkedQueue<>();
    private final Logger logger = new SubstituteLogger("plugin.test", logged, false);

    /** Round only asks whether anybody is inside, so a stand-in counts as the one player there. */
    private static final Set<Player> SOMEBODY = Collections.singleton(null);

    @Test
    void nothingHappensWhileTheInstanceIsPrepared() {
        final Probe round = new Probe(Duration.ZERO);

        round.tick();
        advance(Duration.ofSeconds(5));
        round.tick();

        assertEquals(List.of(), round.calls);
        assertEquals(List.of(), arena.requests);
    }

    @Test
    void theCountdownRunsOutBeforeTheRoundStarts() {
        final Probe round = new Probe(Duration.ofSeconds(3));
        arena.state = ManagedInstance.State.OPEN;

        round.tick();
        advance(Duration.ofSeconds(1));
        round.tick();
        advance(Duration.ofSeconds(1));
        round.tick();
        assertEquals(List.of(), arena.requests, "the round started before its countdown ran out");
        advance(Duration.ofSeconds(1));
        round.tick();

        assertEquals(List.of("onCountdown PT3S", "onCountdown PT2S", "onCountdown PT1S", "readyToStart", "onStart"), round.calls);
        assertEquals(List.of("start"), arena.requests);
    }

    @Test
    void aRoundWaitsUntilItIsReadyToStart() {
        final Probe round = new Probe(Duration.ZERO);
        arena.state = ManagedInstance.State.OPEN;
        round.ready = false;

        round.tick();
        round.tick();
        assertEquals(ManagedInstance.State.OPEN, arena.state, "the round started while it was not ready");
        round.ready = true;
        round.tick();

        assertEquals(List.of("readyToStart", "readyToStart", "readyToStart", "onStart"), round.calls);
        assertEquals(List.of("start"), arena.requests);
    }

    @Test
    void aDecidedRoundShowsItsResultThenSettlesWithItsExits() {
        final Probe round = started();
        arena.players = SOMEBODY;
        advance(Duration.ofSeconds(1));
        round.decision = EndCause.OBJECTIVE;

        round.tick();
        assertEquals(List.of("onTick PT1S", "decided", "onResult OBJECTIVE"), round.calls.subList(2, 5));
        assertEquals(List.of("start", "end OBJECTIVE"), arena.requests);
        advance(RESULT.minusMillis(1));
        round.tick();
        assertEquals(ManagedInstance.State.ENDING, arena.state, "the result was cut short");
        advance(Duration.ofMillis(1));
        round.tick();

        assertEquals(List.of("start", "end OBJECTIVE", "settled"), arena.requests);
        assertSame(round.exit, exitOf(null), "the instance was settled with exits that are not the round's");
        round.tick();
        assertEquals(3, arena.requests.size(), "a closed round still asked for something");
        assertTrue(logged.isEmpty(), "nothing failed, yet a line was written");
    }

    @Test
    void aRoundEndsAtItsTimeLimit() {
        final Probe round = started();
        advance(LENGTH.minusMillis(1));
        round.tick();
        assertEquals(List.of("start"), arena.requests, "the round ended before its time was up");
        advance(Duration.ofMillis(1));
        round.tick();

        assertEquals(List.of("start", "end TIME_LIMIT"), arena.requests);
        assertEquals("onResult TIME_LIMIT", round.calls.getLast());
    }

    @Test
    void anEndedRoundNobodyIsLeftInSettlesAtOnce() {
        final Probe round = started();
        round.decision = EndCause.EMPTY;
        round.tick();
        round.tick();

        assertEquals(List.of("start", "end EMPTY", "settled"), arena.requests);
    }

    @Test
    void aSettleIsNotRequestedTwiceWhileTheHostEvacuates() {
        final Probe round = started();
        arena.evacuating = true;
        round.decision = EndCause.EMPTY;
        round.tick();
        round.tick();
        round.tick();

        assertEquals(List.of("start", "end EMPTY", "settled"), arena.requests);
    }

    @Test
    void aCountdownThatThrowsCancelsTheRound() {
        final Probe round = new Probe(Duration.ofSeconds(3));
        arena.state = ManagedInstance.State.OPEN;
        round.failing = "onCountdown";

        round.tick();

        assertEquals(List.of("cancel"), arena.requests);
        assertSame(round.exit, exitOf(null));
        assertOneFailure("onCountdown");
    }

    @Test
    void aReadyCheckThatThrowsCancelsTheRound() {
        final Probe round = new Probe(Duration.ZERO);
        arena.state = ManagedInstance.State.OPEN;
        round.failing = "readyToStart";

        round.tick();

        assertEquals(List.of("cancel"), arena.requests);
        assertOneFailure("readyToStart");
    }

    @Test
    void aStartThatThrowsEndsTheRoundWithErrorAndSettlesIt() {
        final Probe round = new Probe(Duration.ZERO);
        arena.state = ManagedInstance.State.OPEN;
        round.failing = "onStart";

        round.tick();

        assertEquals(List.of("start", "end ERROR", "settled"), arena.requests);
        assertOneFailure("onStart");
    }

    /** §19 "Round 回调抛异常": the round ends with error and closes at once, and nothing is scored. */
    @Test
    void aTickThatThrowsEndsTheRoundWithErrorAndSettlesItWithoutAResult() {
        final Probe round = started();
        round.failing = "onTick";

        round.tick();

        assertEquals(List.of("start", "end ERROR", "settled"), arena.requests);
        assertEquals(ManagedInstance.State.CLOSED, arena.state);
        assertTrue(round.calls.stream().noneMatch(call -> call.startsWith("onResult") || call.equals("decided")),
                round.calls.toString());
        assertSame(round.exit, exitOf(null), "the players were not sent where the round sends them");
        assertOneFailure("onTick");
    }

    @Test
    void aDecisionThatThrowsEndsTheRoundWithError() {
        final Probe round = started();
        round.failing = "decided";

        round.tick();

        assertEquals(List.of("start", "end ERROR", "settled"), arena.requests);
        assertOneFailure("decided");
    }

    @Test
    void aDecisionOfACauseThatEndsNoRoundCountsAsAFailedCallback() {
        final Probe round = started();
        round.decision = EndCause.CANCELLED;

        round.tick();

        assertEquals(List.of("start", "end ERROR", "settled"), arena.requests);
        assertEquals(1, logged.size());
        final SubstituteLoggingEvent line = logged.element();
        assertEquals(Level.ERROR, line.getLevel());
        assertTrue(line.getMessage().contains("does not end a round"), line.getMessage());
        assertEquals(List.of("test:round_1", EndCause.CANCELLED), List.of(line.getArgumentArray()));
    }

    @Test
    void aDecisionOfErrorClosesTheRoundWithoutAResult() {
        final Probe round = started();
        round.decision = EndCause.ERROR;

        round.tick();

        assertEquals(List.of("start", "end ERROR", "settled"), arena.requests);
        assertTrue(round.calls.stream().noneMatch(call -> call.startsWith("onResult")), round.calls.toString());
        assertTrue(logged.isEmpty());
    }

    @Test
    void aResultThatThrowsSettlesTheRoundAtOnce() {
        final Probe round = started();
        arena.players = SOMEBODY;
        round.decision = EndCause.OBJECTIVE;
        round.failing = "onResult";

        round.tick();

        assertEquals(List.of("start", "end OBJECTIVE", "settled"), arena.requests);
        assertOneFailure("onResult");
    }

    @Test
    void anExitThatThrowsKicksThatPlayer() {
        final Probe round = started();
        round.decision = EndCause.EMPTY;
        round.failing = "exitOf";
        round.tick();
        round.tick();

        final Exit exit = exitOf(null);

        assertEquals(new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE), assertInstanceOf(Exit.Kick.class, exit));
        assertOneFailure("exitOf");
    }

    @Test
    void theDurationsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Probe(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Probe(Duration.ZERO, Duration.ZERO, RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> new Probe(Duration.ZERO, LENGTH, Duration.ofMillis(-1)));
    }

    private Probe started() {
        final Probe round = new Probe(Duration.ZERO);
        arena.state = ManagedInstance.State.OPEN;
        round.tick();
        assertEquals(ManagedInstance.State.RUNNING, arena.state, "the default readyToStart() held the start");
        assertSame(arena, round.arena());
        return round;
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    private Exit exitOf(@Nullable Player player) {
        final Function<Player, Exit> exit = arena.exit;
        assertNotNull(exit, "the instance was never closed");
        return exit.apply(player);
    }

    private void assertOneFailure(String callback) {
        assertEquals(1, logged.size(), "one failed callback writes one line");
        final SubstituteLoggingEvent line = logged.element();
        assertEquals(Level.ERROR, line.getLevel());
        assertEquals("test:round_1", line.getArgumentArray()[0]);
        assertEquals(callback, line.getArgumentArray()[1]);
        assertInstanceOf(IllegalStateException.class, line.getThrowable());
    }

    /** A round recording what it was asked, with one callback made to throw. */
    private final class Probe extends Round {

        final List<String> calls = new ArrayList<>();
        final Exit exit = new Exit.Kick("probe:exit");
        /** Null for the default answer of {@link Round#readyToStart()}. */
        @Nullable Boolean ready;
        @Nullable EndCause decision;
        @Nullable String failing;

        Probe(Duration countdown) {
            this(countdown, LENGTH, RESULT);
        }

        Probe(Duration countdown, Duration length, Duration result) {
            super(arena, countdown, length, result, clock::get, logger);
        }

        @Override
        protected boolean readyToStart() {
            call("readyToStart");
            final Boolean answer = ready;
            return answer == null ? super.readyToStart() : answer;
        }

        @Override
        protected void onCountdown(Duration left) {
            call("onCountdown " + left);
        }

        @Override
        protected void onStart() {
            call("onStart");
        }

        @Override
        protected void onTick(Duration elapsed) {
            call("onTick " + elapsed);
        }

        @Override
        protected @Nullable EndCause decided() {
            call("decided");
            return decision;
        }

        @Override
        protected void onResult(EndCause cause) {
            call("onResult " + cause);
        }

        @Override
        protected Exit exitOf(Player player) {
            call("exitOf");
            return exit;
        }

        private void call(String name) {
            calls.add(name);
            final String thrower = failing;
            if (thrower != null && name.startsWith(thrower)) throw new IllegalStateException("probe: " + name);
        }
    }

    /**
     * The host's managed instance as far as a round sees it: the states of §11.1 and the requests that
     * move them, refused from a state they do not leave.
     */
    private static final class FakeArena implements ManagedInstance {

        final List<String> requests = new ArrayList<>();
        State state = State.PREPARING;
        @Nullable EndCause cause;
        @Nullable Function<Player, Exit> exit;
        Set<Player> players = Set.of();
        /** The state stays put after a cancel or a settle, as while the host evacuates. */
        boolean evacuating;

        @Override
        public String id() {
            return "test:round_1";
        }

        @Override
        public UUID instanceId() {
            return new UUID(0, 1);
        }

        @Override
        public InstanceContainer instance() {
            throw new UnsupportedOperationException("a round never touches the engine instance");
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public @Nullable EndCause endCause() {
            return cause;
        }

        @Override
        public Set<Player> players() {
            return players;
        }

        @Override
        public CompletableFuture<Boolean> move(Player player, Pos at) {
            throw new UnsupportedOperationException("a round moves nobody");
        }

        @Override
        public void start() {
            require(State.OPEN);
            requests.add("start");
            state = State.RUNNING;
        }

        @Override
        public void cancel(Function<Player, Exit> exit) {
            require(State.OPEN);
            requests.add("cancel");
            this.exit = exit;
            cause = EndCause.CANCELLED;
            if (!evacuating) state = State.CLOSED;
        }

        @Override
        public void end(EndCause cause) {
            require(State.RUNNING);
            requests.add("end " + cause);
            this.cause = cause;
            state = State.ENDING;
        }

        @Override
        public void settled(Function<Player, Exit> exit) {
            require(State.ENDING);
            requests.add("settled");
            this.exit = exit;
            if (!evacuating) state = State.CLOSED;
        }

        private void require(State expected) {
            if (state != expected) throw new IllegalStateException("the instance is " + state + ", not " + expected);
        }
    }
}
