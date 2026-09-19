package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.network.ConnectionManager;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the plugin keeps for the connections that are playing: one {@link GameSession} each, made
 * when the admission provider lets a connection in and given back when it goes away.
 * <p>
 * It is its own object because two parts of the plugin read the same table for different reasons:
 * {@link GameChannel} judges what a connection asks for against its team and its budgets, and
 * {@link Units} needs the same team to decide whether the unit it is announcing is that player's to
 * command ({@code flags} bit 0 of {@code S2C_UNIT_SPAWN}).
 * <p>
 * A client cannot make it grow beyond the connections it holds open — §15 caps the heap, and an
 * entry nobody reclaims would put that cap in the client's hands.
 */
final class GameSessions {

    private final ConnectionManager connections;
    private final Map<UUID, GameSession> sessions = new ConcurrentHashMap<>();

    GameSessions(ConnectionManager connections) {
        this.connections = connections;
    }

    /**
     * Records the team a connection was admitted with, which is the sender half of every
     * {@code owner(unit, sender)} test (§9). Called on the configuration virtual thread, from
     * inside the admission provider.
     * <p>
     * A connection that is already gone gets no entry. Testing that and making the entry have to be
     * one critical section, shared with {@link #closed}: the disconnect that reclaims an entry runs
     * on another thread, and an insertion that overtook it would leave an entry nobody ever gives
     * back — a client would then only have to close the socket while it is being admitted, as often
     * as it likes ({@code ConnectionManager#trackPlayer} is the same shape).
     *
     * @param connectionId the connection being let in
     * @param team         the team it plays for
     */
    synchronized void admitted(UUID connectionId, int team) {
        if (!connections.isConnected(connectionId)) return;
        sessions.computeIfAbsent(connectionId, _ -> new GameSession()).team(team);
    }

    /**
     * Gives back what is held for one connection (§11.1 {@code in_game → closed}).
     *
     * @param connectionId the connection that went away
     */
    synchronized void closed(UUID connectionId) {
        sessions.remove(connectionId);
    }

    /**
     * Counts what is being held, which is the invariant the reclamation has to keep.
     *
     * @return how many connections still have a session
     */
    int open() {
        return sessions.size();
    }

    /**
     * The bookkeeping of one connection.
     *
     * @param connectionId the connection to look up
     * @return its session, null when nothing is held for it
     */
    @Nullable GameSession get(UUID connectionId) {
        return sessions.get(connectionId);
    }

    /**
     * The team of one connection.
     *
     * @param connectionId the connection to look up
     * @return its team, and 0 — the neutral team, which commands nothing — for a connection with no
     *         session at all
     */
    int team(UUID connectionId) {
        final GameSession session = sessions.get(connectionId);
        return session == null ? 0 : session.team();
    }
}
