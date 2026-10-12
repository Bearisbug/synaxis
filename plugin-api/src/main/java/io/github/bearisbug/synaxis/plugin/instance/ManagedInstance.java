package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One instance whose lifecycle the host runs ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, the
 * state machine of §11.1).
 * <p>
 * Every transition is carried out by the host, which writes a ledger line for it and calls a
 * {@link PlayInstanceStateEvent}. {@link #start}, {@link #cancel}, {@link #end} and {@link #settled}
 * are requests: they are called on the tick scheduler thread or on the tick thread of the instance,
 * and throw {@link IllegalStateException} from a state the transition does not leave.
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
     * The engine's id of the instance.
     *
     * @return the instance UUID
     */
    UUID instanceId();

    /**
     * The engine instance. It is unregistered until the instance turns {@link State#OPEN}, and again
     * once it is {@link State#CLOSED}.
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
     * Moves a player into this instance. May be called from any thread; the move itself runs on the
     * tick thread that owns the player, and the scene layer holds back what it sends that player
     * until the move is done.
     *
     * @param player the player
     * @param at     where the player lands
     * @return completes on the player's tick thread with true once the player is in, with false if
     *         the instance is not {@link State#OPEN} or {@link State#RUNNING} or the player left
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
     * {@link EndCause#CANCELLED}, after the players are sent away.
     *
     * @param exit where each player goes
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
     * {@link #end}, after the players are sent away.
     *
     * @param exit where each player goes
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
        /** Evacuated and unregistered, or dropped before it was ever registered. */
        CLOSED
    }
}
