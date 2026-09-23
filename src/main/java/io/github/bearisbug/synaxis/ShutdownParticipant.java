package io.github.bearisbug.synaxis;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The save half of the shutdown sequence ({@code docs/DESIGN-PROD.md} API-PROD-007, §11.4).
 * <p>
 * The engine owns the phases and the clock; what gets saved, and where, belongs to the host that
 * registered this, since the engine knows no database. Every method is called on the thread that
 * runs the shutdown sequence, while the tick threads are still running.
 */
public interface ShutdownParticipant {

    /**
     * The login gate has just closed: new logins are turned away with
     * {@code synaxis:server-stopping}. The countdown starts now.
     *
     * @param countdown how long until the save phase, zero for none
     */
    void onGateClosed(Duration countdown);

    /**
     * Saves everything that has to survive the shutdown.
     * <p>
     * Called once when the save phase starts. It is called a second time, with
     * {@code tickAlive=false} and the shorter budget of a dead process, when the tick watchdog
     * judges the process dead while the first save is still running: whatever the first call still
     * wanted to read on a tick thread has to be read on this thread instead.
     *
     * @param budget    how long the engine waits for the returned future
     * @param tickAlive false when the tick is stuck: nothing may wait for a tick thread
     * @return completes once nothing is left to save
     */
    CompletableFuture<?> saveAll(Duration budget, boolean tickAlive);

    /**
     * The save phase is over, whether or not the future completed. Called once.
     *
     * @param timedOut true if the budget ran out, or the watchdog cut it short, before the future
     *                 of {@link #saveAll} completed
     */
    void onSaveEnded(boolean timedOut);
}
