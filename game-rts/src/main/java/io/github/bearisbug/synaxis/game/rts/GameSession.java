package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.game.protocol.C2SSelect;

import java.util.Set;

/**
 * What the server keeps for one connection that is playing: its team, what it has selected, how far
 * its order sequence has got, and the budgets of {@code docs/DESIGN-M2.md} §15.
 * <p>
 * <b>Two kinds of limit live here and they must not be confused.</b> The per second budgets are
 * gameplay limits: going over one costs the client an answer of {@code rate-limited} or a dropped
 * camera report, never its connection. The flood gate is a different thing with a much higher
 * threshold — it is the only path in this class that ends in a disconnect, and it exists against
 * scripted floods rather than against a fast player (§14).
 * <p>
 * <b>The order budget counts operations, not packets.</b> One click on a group of units produces
 * one packet per unit sharing one {@code order_group}, so a bucket that counted packets would
 * refuse half of a single click while the design says a click costs one token
 * ({@code API-M2-005}).
 * <p>
 * Everything except {@link #team()} is read and written on the tick thread, which interprets the
 * inbound packets of one connection one at a time. The team is written once, on the configuration
 * virtual thread that admitted the connection, hence the only volatile field.
 */
final class GameSession {

    /** §15: camera reports, selections and order operations each get ten a second. */
    private static final int BUDGET_PER_SECOND = 10;

    /**
     * §8: the packets of one operation are capped at what one selection can carry, so that the
     * burst a single click may produce stays bounded whatever the client does with the group id.
     */
    private static final int PACKETS_PER_OPERATION = C2SSelect.MAX_UNITS;

    /** §14: over this many game payloads in a second, a second counts towards a flood. */
    private static final int FLOOD_PACKETS_PER_SECOND = 300;

    /** §14: how many such seconds in a row make a flood. */
    private static final int FLOOD_SECONDS = 3;

    /** No operation is in progress; no {@code order_group} can take this value (§9 has it ≥ 0). */
    private static final int NO_OPERATION = -1;

    /** Before the first order is accepted every sequence number is new, including zero. */
    private static final int NO_ORDER = -1;

    private volatile int team;

    private float anchorX;
    private float anchorZ;

    private Set<Integer> selection = Set.of();

    private int acceptedOrderSeq = NO_ORDER;

    private long cameraSecond = Long.MIN_VALUE;
    private int cameraCount;

    private long selectSecond = Long.MIN_VALUE;
    private int selectCount;

    private long orderSecond = Long.MIN_VALUE;
    private int operationsThisSecond;
    private int operation = NO_OPERATION;
    private boolean operationGranted;
    private int packetsOfOperation;

    private long floodSecond = Long.MIN_VALUE;
    private int floodCount;
    private int exceededSeconds;

    int team() {
        return team;
    }

    void team(int team) {
        this.team = team;
    }

    float anchorX() {
        return anchorX;
    }

    float anchorZ() {
        return anchorZ;
    }

    void camera(float anchorX, float anchorZ) {
        this.anchorX = anchorX;
        this.anchorZ = anchorZ;
    }

    Set<Integer> selection() {
        return selection;
    }

    /**
     * Replaces the selection wholesale, which is the semantics of {@code API-M2-004}.
     *
     * @param units the units this connection now has selected
     */
    void select(Set<Integer> units) {
        this.selection = Set.copyOf(units);
    }

    int acceptedOrderSeq() {
        return acceptedOrderSeq;
    }

    /**
     * Moves the sequence on, which only an accepted order does: a refused one leaves its number
     * available, so a client that had an order turned down may reuse it.
     *
     * @param orderSeq the sequence number of the accepted order
     */
    void acceptOrder(int orderSeq) {
        this.acceptedOrderSeq = orderSeq;
    }

    /**
     * Spends one camera token.
     *
     * @param second the second this report arrived in
     * @return true when the report is within the budget; the caller drops it otherwise
     */
    boolean allowCamera(long second) {
        if (second != cameraSecond) {
            cameraSecond = second;
            cameraCount = 0;
        }
        return ++cameraCount <= BUDGET_PER_SECOND;
    }

    /**
     * Spends one selection token.
     *
     * @param second the second this selection arrived in
     * @return true when the selection is within the budget; the caller drops it otherwise
     */
    boolean allowSelect(long second) {
        if (second != selectSecond) {
            selectSecond = second;
            selectCount = 0;
        }
        return ++selectCount <= BUDGET_PER_SECOND;
    }

    /**
     * Charges one order packet against the operation budget.
     * <p>
     * A token is spent when the {@code order_group} changes, not when a packet arrives, so the
     * further packets of an operation ride on the token their first one took — and the further
     * packets of an operation that found the bucket empty stay refused rather than slipping through
     * behind a later refill.
     *
     * @param second     the second this order arrived in
     * @param orderGroup the operation it belongs to
     * @return true when the order may be judged on its merits; false when it is rate limited
     */
    boolean allowOrder(long second, int orderGroup) {
        if (second != orderSecond) {
            orderSecond = second;
            operationsThisSecond = 0;
            operation = NO_OPERATION;
        }
        if (orderGroup != operation) {
            operation = orderGroup;
            packetsOfOperation = 0;
            operationGranted = operationsThisSecond < BUDGET_PER_SECOND;
            if (operationGranted) operationsThisSecond++;
        }
        return ++packetsOfOperation <= PACKETS_PER_OPERATION && operationGranted;
    }

    /**
     * Counts one inbound game payload, whatever it turned out to be, and says whether this
     * connection has now been over the flood threshold for {@value #FLOOD_SECONDS} seconds running.
     * <p>
     * A second is judged when it ends, so the run is only ever made of seconds that really carried
     * that many payloads; a second with nothing in it breaks the run, which is what keeps a client
     * that bursts once a minute out of the gate.
     *
     * @param second the second this payload arrived in
     * @return true when the connection has to be disconnected
     */
    boolean payloadFloods(long second) {
        if (second != floodSecond) {
            final boolean continues = second == floodSecond + 1 && floodCount > FLOOD_PACKETS_PER_SECOND;
            exceededSeconds = continues ? exceededSeconds + 1 : 0;
            floodSecond = second;
            floodCount = 0;
        }
        return ++floodCount > FLOOD_PACKETS_PER_SECOND && exceededSeconds >= FLOOD_SECONDS - 1;
    }
}
