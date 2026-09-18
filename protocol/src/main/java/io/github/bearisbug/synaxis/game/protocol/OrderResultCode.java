package io.github.bearisbug.synaxis.game.protocol;

/**
 * The {@code order_result_code} of {@code docs/DESIGN-M2.md} §9, carried by {@link S2COrderResult}.
 * <p>
 * It is a result code, not a lifecycle state: it is the projection of the {@code ACCEPT(order)}
 * conjunction of §11.4, and the wire values follow the short circuit order that section evaluates
 * in. None of them disconnects anybody — §14 keeps gameplay rejections in band.
 */
public enum OrderResultCode {

    /** The order was accepted; {@code snapped_*} is the real destination. */
    OK(0),
    /** {@code order_seq} was not greater than the largest one already accepted. */
    STALE(1),
    /** The connection went over ten orders per second, counted per {@code order_group}. */
    RATE_LIMITED(2),
    /** The unit does not belong to the sender's team. */
    NOT_OWNER(3),
    /** The target is off the map or not standable. */
    OUT_OF_BOUNDS(4),
    /** The pathfinding queue is full. */
    BUSY(5),
    /** The target is standable but not reachable. */
    NO_PATH(6);

    private final int wireId;

    OrderResultCode(int wireId) {
        this.wireId = wireId;
    }

    /**
     * The u8 this code is encoded as.
     *
     * @return the wire value
     */
    public int wireId() {
        return wireId;
    }

    /**
     * Decodes an {@code order_result_code} byte.
     *
     * @param wireId the encoded value
     * @return the matching code
     * @throws MalformedMessageException if no code carries that value
     */
    public static OrderResultCode fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> OK;
            case 1 -> STALE;
            case 2 -> RATE_LIMITED;
            case 3 -> NOT_OWNER;
            case 4 -> OUT_OF_BOUNDS;
            case 5 -> BUSY;
            case 6 -> NO_PATH;
            default -> throw new MalformedMessageException("unknown order_result_code: " + wireId);
        };
    }
}
