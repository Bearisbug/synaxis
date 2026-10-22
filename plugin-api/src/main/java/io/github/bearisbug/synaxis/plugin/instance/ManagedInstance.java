package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One instance whose lifecycle the host runs ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, the
 * state machine of §11.1).
 * <p>
 * Every transition is carried out by the host, which writes a ledger line for it and calls a
 * {@link PlayInstanceStateEvent}. {@link #start}, {@link #cancel}, {@link #end} and {@link #settled}
 * are requests, and throw {@link IllegalStateException} from a state the transition does not leave.
 * Call them in {@code onEnable}, on the tick scheduler thread or on the tick thread of the instance.
 * The calling thread is not checked, since the state swap is atomic and the host unregisters the
 * instance only on the startup thread or in the serial part of the tick; but the
 * {@link PlayInstanceStateEvent} of the transition is dispatched on the calling thread, and
 * {@code cancel} and {@code settled} call the exit function for each player there too. Called from
 * another thread, those listeners and that function run on it.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface ManagedInstance {

    /**
     * The id of the instance, {@code <plugin_id>:<name>_<n>}, which is also its dimension name.
     *
     * @return the id
     */
    String id();

    /**
     * The engine instance. It is unregistered until the instance turns {@link State#OPEN}, and again
     * once the evacuation that follows {@link State#CLOSED} is done: a closed instance stays
     * registered while its players are sent away, at most for the host's evacuation time limit.
     *
     * @return the engine instance
     */
    InstanceContainer instance();

    /**
     * The current state.
     *
     * @return the state
     */
    State state();

    /**
     * Why the instance ended or closed.
     *
     * @return the cause, null before {@link State#ENDING} or {@link State#CLOSED}
     */
    @Nullable EndCause endCause();

    /**
     * The players in the instance.
     *
     * @return a snapshot of them
     */
    Set<Player> players();

    /**
     * How long the instance may stay {@link State#ENDING} before the host closes it with the fallback
     * exit of its spec, {@link InstanceSpec#settleTimeout()}.
     *
     * @return the settle timeout
     */
    Duration settleTimeout();

    /**
     * Moves a player into this instance. May be called from any thread; the move itself runs on the
     * tick thread that owns the player, and the scene layer holds back what it sends that player
     * until the move is done.
     *
     * @param player the player
     * @param at     where the player lands; not used for a player already in this instance, for whom
     *               the future completes with whether the instance still takes players
     *               ({@link State#OPEN} or {@link State#RUNNING}): {@code Player#teleport} moves a
     *               player within the instance
     * @return completes with true once the player is in, with false if the instance is not
     *         {@link State#OPEN} or {@link State#RUNNING}, the player left, a listener kept the
     *         player out, a later change of instance took the place of this one, or the engine
     *         failed to set the instance; it never completes exceptionally. It completes on the
     *         calling thread when the instance takes nobody at the call, on the tick scheduler thread
     *         when the player left before the move ran, and otherwise where the engine finishes
     *         setting the instance: the player's tick thread when the landing chunks are loaded,
     *         the tick scheduler thread when they have to be loaded first
     */
    CompletableFuture<Boolean> move(Player player, Pos at);

    /**
     * Starts the instance: {@link State#OPEN} to {@link State#RUNNING}.
     *
     * @throws IllegalStateException if the instance is not {@code OPEN}
     */
    void start();

    /**
     * Closes an instance that never started: {@link State#OPEN} to {@link State#CLOSED}, cause
     * {@link EndCause#CANCELLED}. The instance is {@code CLOSED} as soon as this returns; the players
     * are then sent away and the instance unregistered.
     *
     * @param exit where each player goes; an exit into another managed instance names one that is
     *             {@link State#OPEN} or {@link State#RUNNING}, and a player whose move there
     *             completes with false is kicked with {@code synaxis:match-unavailable}, as is one the
     *             function throws for or gives null. It is called at most once per player: for those online
     *             inside at the close before this returns, on the calling thread; for one whose move into
     *             this instance lands after the close, on the thread that move completes on (the player's
     *             tick thread or the tick scheduler thread), up to the evacuation time limit later, 5 s by
     *             default, and possibly at the same time as a call on another thread. So it should read only
     *             state that is thread safe and not cleared once the instance closes
     * @throws IllegalStateException if the instance is not {@code OPEN}
     */
    void cancel(Function<Player, Exit> exit);

    /**
     * Ends a running instance: {@link State#RUNNING} to {@link State#ENDING}. It takes no more
     * players and the settle timeout of its spec starts.
     *
     * @param cause one of {@link EndCause#OBJECTIVE}, {@link EndCause#TIME_LIMIT},
     *              {@link EndCause#EMPTY} and {@link EndCause#ERROR}
     * @throws IllegalStateException    if the instance is not {@code RUNNING}
     * @throws IllegalArgumentException if the cause is not one of those four
     */
    void end(EndCause cause);

    /**
     * Closes an ended instance: {@link State#ENDING} to {@link State#CLOSED}, keeping the cause of
     * {@link #end}. The instance is {@code CLOSED} as soon as this returns; the players are then sent
     * away and the instance unregistered.
     *
     * @param exit where each player goes, as for {@link #cancel}
     * @throws IllegalStateException if the instance is not {@code ENDING}
     */
    void settled(Function<Player, Exit> exit);

    /**
     * The states of a managed instance (§11.1).
     */
    enum State {

        /** Created; chunks are being generated and lit, nobody can be moved in. */
        PREPARING,
        /** Registered and ready; players can be moved in. */
        OPEN,
        /** Started by the plugin. */
        RUNNING,
        /** Ended by the plugin, waiting for it to settle; no more players are moved in. */
        ENDING,
        /**
         * Closed: no more players are moved in, and the host evacuates and then unregisters it
         * within its evacuation time limit; or dropped before it was ever registered.
         */
        CLOSED
    }
}
