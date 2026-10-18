package io.github.bearisbug.synaxis.game.playkit;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One round of a game, played in a managed instance ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}):
 * the round counts down, starts, runs, ends and settles the instance, and asks the subclass for the
 * rules at each step.
 * <p>
 * The plugin calls {@link #tick()} once per tick from a task of {@code scheduleTask}, which runs in the
 * serial part of the tick, where the transitions of a {@link ManagedInstance} may be requested.
 * <p>
 * Every call into the subclass is guarded. A callback that throws closes the round without settling
 * it: an open round is cancelled, a running one ends with {@link EndCause#ERROR} and is settled at
 * once, an ended one is settled at once. The players go where {@link #exitOf} sends them, and one
 * ERROR line on the given logger names the instance and the callback. Left to the event wrapper, a
 * callback failing on every tick would only be logged, and the round would stay in its phase.
 * <p>
 * Every transition of the instance is requested by the round: a round ends by {@link #decided()}
 * returning a cause, and one that never started is given up with {@link #cancel()}. A subclass that
 * calls {@code start}, {@code end}, {@code cancel} or {@code settled} on {@link #arena()} itself leaves
 * the round out of step with its instance: {@link #onResult} is not called, or the start throws.
 */
public abstract class Round {

    private final ManagedInstance arena;
    private final Duration countdown;
    private final Duration length;
    private final Duration result;
    private final LongSupplier nanoClock;
    private final Logger logger;

    private boolean opened;
    private long openedAt;
    private long startedAt;
    private long endingAt;

    /**
     * Creates the round of an instance.
     *
     * @param arena     the instance the round is played in
     * @param countdown how long the instance stays open before the round starts, zero for at once
     * @param length    how long the round runs before it ends with {@link EndCause#TIME_LIMIT}
     * @param result    how long an ended round shows its result before it is settled, at least 1 s
     *                  shorter than the settle timeout of the arena, after which the host closes it with
     *                  the fallback exit of its spec
     * @param nanoClock the monotonic clock, in nanoseconds
     * @param logger    where the line of a failed callback goes, the plugin's logger
     * @throws IllegalArgumentException if the countdown or the result is negative, the length is not
     *                                  more than zero, or the result is not at least 1 s shorter than
     *                                  the settle timeout of the arena
     */
    protected Round(ManagedInstance arena, Duration countdown, Duration length, Duration result,
                    LongSupplier nanoClock, Logger logger) {
        if (countdown.isNegative())
            throw new IllegalArgumentException("a countdown is not negative, not " + countdown);
        if (!length.isPositive())
            throw new IllegalArgumentException("a round lasts more than zero, not " + length);
        if (result.isNegative())
            throw new IllegalArgumentException("a result is not shown for a negative time, not " + result);
        if (result.plusSeconds(1).compareTo(arena.settleTimeout()) >= 0)
            throw new IllegalArgumentException("a result shown for " + result + " needs a settle timeout over "
                    + result.plusSeconds(1) + ", and " + arena.id() + " has " + arena.settleTimeout()
                    + ": give its InstanceSpec a longer settleTimeout with the canonical constructor");
        this.arena = arena;
        this.countdown = countdown;
        this.length = length;
        this.result = result;
        this.nanoClock = nanoClock;
        this.logger = logger;
    }

    /**
     * The instance of this round, to read: its transitions are the round's to request (see the class
     * description).
     *
     * @return the instance
     */
    public final ManagedInstance arena() {
        return arena;
    }

    /**
     * Moves the round on by one tick, from the state of its instance: nothing while it is
     * {@code PREPARING}; while it is {@code OPEN}, the countdown, then {@code start()} once
     * {@link #readyToStart()} agrees; while it is {@code RUNNING}, {@link #onTick} and
     * {@link #decided()}, and {@code end(TIME_LIMIT)} when the length is up; while it is
     * {@code ENDING}, {@code settled} once the result was shown long enough or nobody is left inside.
     * Once the instance is {@code CLOSED} the round is over and the caller drops it.
     */
    public final void tick() {
        final long now = nanoClock.getAsLong();
        switch (arena.state()) {
            case PREPARING, CLOSED -> {
            }
            case OPEN -> open(now);
            case RUNNING -> run(now);
            case ENDING -> show(now);
        }
    }

    /**
     * Whether the round may start once the countdown is over. A game that moves the players in while
     * the instance is open answers false until every move is done, so that the first running tick does
     * not find the instance empty.
     *
     * @return true to start, by default always
     */
    protected boolean readyToStart() {
        return true;
    }

    /**
     * Called on every tick of the countdown.
     *
     * @param left the time left, more than zero
     */
    protected abstract void onCountdown(Duration left);

    /**
     * Called once, right after the instance started; the place to take a snapshot of what the round
     * keeps to its end.
     */
    protected abstract void onStart();

    /**
     * Called on every tick while the round runs.
     *
     * @param elapsed the time since the round started
     */
    protected abstract void onTick(Duration elapsed);

    /**
     * Called on every tick while the round runs, after {@link #onTick}.
     *
     * @return why the round ends now, such as {@link EndCause#OBJECTIVE} or {@link EndCause#EMPTY};
     *         null to go on. A cause that does not end a round counts as a failed callback
     */
    protected abstract @Nullable EndCause decided();

    /**
     * Called once when the round ended for any cause but {@link EndCause#ERROR}: the place to settle
     * the scores and show them.
     *
     * @param cause why the round ended
     */
    protected abstract void onResult(EndCause cause);

    /**
     * Where a player goes when the instance closes.
     *
     * @param player the player
     * @return the exit of {@code player}
     */
    protected abstract Exit exitOf(Player player);

    private void open(long now) {
        if (!opened) {
            opened = true;
            openedAt = now;
        }
        final Duration left = countdown.minusNanos(now - openedAt);
        if (left.isPositive()) {
            if (failed("onCountdown", () -> onCountdown(left))) cancel();
            return;
        }
        final boolean ready;
        try {
            ready = readyToStart();
        } catch (Throwable e) {
            report("readyToStart", e);
            cancel();
            return;
        }
        if (!ready) return;
        arena.start();
        startedAt = now;
        if (failed("onStart", this::onStart)) abort();
    }

    private void run(long now) {
        final Duration elapsed = Duration.ofNanos(now - startedAt);
        if (failed("onTick", () -> onTick(elapsed))) {
            abort();
            return;
        }
        final @Nullable EndCause cause;
        try {
            cause = decided();
        } catch (Throwable e) {
            report("decided", e);
            abort();
            return;
        }
        if (cause != null) end(cause, now);
        else if (elapsed.compareTo(length) >= 0) end(EndCause.TIME_LIMIT, now);
    }

    private void end(EndCause cause, long now) {
        switch (cause) {
            case OBJECTIVE, TIME_LIMIT, EMPTY -> {
                arena.end(cause);
                endingAt = now;
                if (failed("onResult", () -> onResult(cause))) settle();
            }
            case ERROR -> abort();
            default -> {
                logger.error("A round callback returned a cause that does not end a round: instance={} "
                        + "callback=decided cause={}", arena.id(), cause);
                abort();
            }
        }
    }

    private void show(long now) {
        if (now - endingAt >= result.toNanos() || arena.players().isEmpty()) settle();
    }

    /**
     * Gives up a round that never started: the instance is cancelled and its players go where
     * {@link #exitOf} sends them. Called while the instance is {@code OPEN}, from {@link #onCountdown}
     * or {@link #readyToStart()}; the instance is {@code CLOSED} once this returns, so {@link #tick()}
     * does nothing more with it. A {@link #readyToStart()} that cancels then answers false: true would
     * start an instance that is no longer {@code OPEN}, and {@link #tick()} throws the instance's
     * {@link IllegalStateException}. Called while it runs, the instance refuses with an
     * {@link IllegalStateException}, which from a callback closes the round as a failed callback.
     */
    protected final void cancel() {
        arena.cancel(this::exit);
    }

    private void settle() {
        arena.settled(this::exit);
    }

    /** A running round whose rules failed: ended with ERROR and settled at once, nothing is scored. */
    private void abort() {
        arena.end(EndCause.ERROR);
        settle();
    }

    private Exit exit(Player player) {
        try {
            return exitOf(player);
        } catch (Throwable e) {
            report("exitOf", e);
            return new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE);
        }
    }

    private boolean failed(String callback, Runnable body) {
        try {
            body.run();
            return false;
        } catch (Throwable e) {
            report(callback, e);
            return true;
        }
    }

    private void report(String callback, Throwable e) {
        logger.error("A round callback threw: instance={} callback={} state={}", arena.id(), callback, arena.state(), e);
    }
}
