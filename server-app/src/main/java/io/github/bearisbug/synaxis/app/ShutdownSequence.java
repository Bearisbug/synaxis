package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one {@link ShutdownParticipant} of the server application ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-007}, §11.4 {@code saving}): the player store saves first, exactly as it would on
 * its own, then the steps registered while the process was assembled run one after the other, in
 * the order they were registered, out of the one budget the engine gives the save phase. Kicking
 * follows once the last step is done or the budget is over.
 * <p>
 * A step still running when the budget runs out is not waited for: the engine moves on to kicking,
 * and the step reports its own failure. A step whose turn never came is logged here.
 */
public final class ShutdownSequence implements ShutdownParticipant {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownSequence.class);

    /** One step of the save phase, after the players. */
    @FunctionalInterface
    public interface Step {

        /**
         * Runs the step. Called once when its turn comes, and once more, with {@code tickAlive=false},
         * when the tick watchdog judges the process dead while the future of the first call is still
         * pending: what the first call left for a tick thread to do has to be done by the second
         * call instead, and the future of the second call stands for both.
         *
         * @param remaining what is left of the save budget, which the step caps itself to
         * @param tickAlive true while the tick runs: the step hands its work to the tick and must not
         *                  block. False once the watchdog judged the tick dead: no tick thread runs
         *                  anything anymore, and the step does its work on the calling thread, the
         *                  thread of the shutdown sequence
         * @return completes once the step is over, done or failed. The sequence logs neither a future
         *         that completes exceptionally nor a step still running when the budget runs out: the step
         *         logs both itself ({@code docs/DESIGN-PROD.md} {@code API-PROD-007}). Only a
         *         {@link RuntimeException} this call throws is logged by the sequence, and the step then
         *         counts as over
         */
        CompletableFuture<?> run(Duration remaining, boolean tickAlive);
    }

    private record Registered(String name, Step step) {
    }

    private final ServerProcess process;
    private final ShutdownParticipant players;
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    // Everything below is guarded by this
    private final List<Registered> steps = new ArrayList<>();
    private long deadline;
    private boolean tickAlive = true;
    private @Nullable CompletableFuture<?> playersSaved;
    // The step whose turn it is: -1 while the players save, steps.size() once every step is over
    private int current = -1;
    // The future of the current step while its call in flight is the one made for a live tick
    private @Nullable CompletableFuture<?> liveCall;
    private boolean ended;

    /**
     * @param process the engine process, which tells when registering is over
     * @param players the player store, the first to save
     */
    public ShutdownSequence(ServerProcess process, ShutdownParticipant players) {
        this.process = process;
        this.players = players;
    }

    /**
     * Registers a step to run after the players, behind the steps registered before it. Only while
     * the process is being assembled: a step registered later could miss a save phase already under
     * way.
     *
     * @param name what the logs call the step
     * @param step the step
     * @throws IllegalStateException if the process has started
     */
    public synchronized void addAfterPlayers(String name, Step step) {
        if (process.isAlive() || process.shutdownPhase() != ServerProcess.ShutdownPhase.RUNNING)
            throw new IllegalStateException("The shutdown step " + name + " is registered after the process started");
        steps.add(new Registered(name, step));
    }

    @Override
    public void onGateClosed(Duration countdown) {
        players.onGateClosed(countdown);
    }

    @Override
    public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
        final long until = System.nanoTime() + budget.toNanos();
        final CompletableFuture<?> saved = players.saveAll(budget, tickAlive);
        final boolean first;
        synchronized (this) {
            deadline = until;
            this.tickAlive = tickAlive;
            first = playersSaved == null;
            playersSaved = saved;
            if (steps.isEmpty()) return saved;
        }
        if (!tickAlive) {
            runDead(saved);
        } else if (first) {
            var _ = saved.whenComplete((_, _) -> advance(-1));
        }
        return done;
    }

    /**
     * Starts the step after {@code finished} for a live tick, from the thread that completed the
     * future of {@code finished}. The call is made under the lock, so that a dead tick taking over
     * never sees a step it has not been told about; a live call only hands work over and returns.
     */
    private void advance(int finished) {
        final int index = finished + 1;
        final CompletableFuture<?> future;
        synchronized (this) {
            // Driven by the shutdown thread since the tick died, over, or a stale completion
            if (!tickAlive || ended || current != finished) return;
            if (index < steps.size()) {
                final long remaining = deadline - System.nanoTime();
                // The budget is over: the engine moves on to kicking without this step
                if (remaining <= 0) return;
                current = index;
                liveCall = call(steps.get(index), remaining, true);
                future = liveCall;
            } else {
                current = index;
                future = null;
            }
        }
        if (future == null) {
            done.complete(null);
        } else {
            var _ = future.whenComplete((_, _) -> advance(index));
        }
    }

    /**
     * The tick is dead: this thread, the one running the shutdown sequence, waits for the players
     * and runs every step left, a step started for a live tick a second time, each within the
     * deadline.
     */
    private void runDead(CompletableFuture<?> saved) {
        if (!await(saved)) return;
        while (true) {
            final Registered step;
            final long remaining;
            synchronized (this) {
                if (ended) return;
                final boolean rerun = liveCall != null && !liveCall.isDone();
                final int index = rerun ? current : current + 1;
                if (index >= steps.size()) {
                    current = steps.size();
                    break;
                }
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                current = index;
                liveCall = null;
                step = steps.get(index);
            }
            if (!await(call(step, remaining, false))) return;
        }
        done.complete(null);
    }

    /**
     * @return false if the deadline passed first
     */
    private boolean await(CompletableFuture<?> future) {
        final long until;
        synchronized (this) {
            until = deadline;
        }
        try {
            future.get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException _) {
            return false;
        } catch (ExecutionException | CancellationException _) {
            // A step that failed is as over as one that completed; it reports its own failure
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static CompletableFuture<?> call(Registered step, long remainingNanos, boolean tickAlive) {
        try {
            return step.step().run(Duration.ofNanos(remainingNanos), tickAlive);
        } catch (RuntimeException e) {
            LOGGER.error("The shutdown step {} failed", step.name(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void onSaveEnded(boolean timedOut) {
        final boolean playersTimedOut;
        final List<String> notRun = new ArrayList<>();
        synchronized (this) {
            ended = true;
            // Without steps the engine's answer is the player store's own. With them the player
            // store is told about its own save: done, it did not time out, whatever a step did.
            playersTimedOut = steps.isEmpty() ? timedOut
                    : timedOut && (playersSaved == null || !playersSaved.isDone());
            for (int i = current + 1; i < steps.size(); i++) notRun.add(steps.get(i).name());
        }
        for (String name : notRun)
            LOGGER.error("The shutdown step {} did not run, the save budget was used up", name);
        players.onSaveEnded(playersTimedOut);
    }
}
