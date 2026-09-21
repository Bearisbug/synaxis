package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import net.minecraft.world.phys.Vec3;

/**
 * What one answer to an order is worth showing, which is the client half of the in band result
 * codes of {@code docs/DESIGN-M2.md} §14.
 * <p>
 * The drawing is stage 6b. What belongs here is the decision the drawing reads: which marker the
 * code earns, where it goes, and whether it says anything — three things §14 writes down and one
 * of which ({@code out-of-bounds} stays wordless because it is the code a player produces most
 * often) is easy to get wrong from memory.
 * <p>
 * The point is the answer's {@code snapped_*}, which is a real destination only when the code is
 * {@link OrderResultCode#OK}; for a refusal it is whatever the server echoed back, and a marker
 * there is what tells the player which click was refused.
 *
 * @param code  the answer, either the server's or — for {@link OrderResultCode#RATE_LIMITED} — the
 *              one the client gave itself rather than sending an order it knows is over budget
 * @param point where to put the marker
 */
public record OrderFeedback(OrderResultCode code, Vec3 point) {

    /** The marker §14 gives a code, or {@link #SILENT} for the one code that shows nothing. */
    public enum Marker {
        /** The order was accepted and this is where the unit is going. */
        GREEN,
        /** The order was refused for something about the target or the unit. */
        RED,
        /** The order was refused for something that passes: the server is busy, or the player is fast. */
        YELLOW,
        /** Nothing is drawn and nothing already drawn is replaced. */
        SILENT
    }

    /**
     * @return the marker to draw
     */
    public Marker marker() {
        return switch (code) {
            case OK -> Marker.GREEN;
            case NO_PATH, NOT_OWNER, OUT_OF_BOUNDS -> Marker.RED;
            case BUSY, RATE_LIMITED -> Marker.YELLOW;
            // A retransmission or a reordering, which is to say a normal event the player did not
            // cause and has nothing to do about.
            case STALE -> Marker.SILENT;
        };
    }

    /**
     * @return the {@code translate} key to show beside the marker, or {@code null} for the codes
     *         §14 leaves wordless — {@code ok} needs no words, {@code out-of-bounds} is frequent
     *         enough that words would be spam, and {@code rate-limited} is the client's own doing
     */
    public String text() {
        return switch (code) {
            case NO_PATH -> "synaxis.order.no-path";
            case NOT_OWNER -> "synaxis.order.not-owner";
            case BUSY -> "synaxis.order.busy";
            case OK, OUT_OF_BOUNDS, RATE_LIMITED, STALE -> null;
        };
    }
}
