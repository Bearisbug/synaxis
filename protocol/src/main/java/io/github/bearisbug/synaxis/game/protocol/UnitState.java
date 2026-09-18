package io.github.bearisbug.synaxis.game.protocol;

/**
 * The {@code unit_state} of {@code docs/DESIGN-M2.md} §9 as it appears in a snapshot entry.
 * <p>
 * {@code removed} is not on the wire: a removed unit leaves through {@link S2CUnitDespawn}, so the
 * snapshot only ever carries these two.
 */
public enum UnitState {

    /** The unit is standing still. */
    IDLE(0),
    /** The unit is following a path. */
    MOVING(1);

    private final int wireId;

    UnitState(int wireId) {
        this.wireId = wireId;
    }

    /**
     * The u8 this state is encoded as.
     *
     * @return the wire value
     */
    public int wireId() {
        return wireId;
    }

    /**
     * Decodes a {@code unit_state} byte.
     *
     * @param wireId the encoded value
     * @return the matching state
     * @throws MalformedMessageException if no state carries that value
     */
    public static UnitState fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> IDLE;
            case 1 -> MOVING;
            default -> throw new MalformedMessageException("unknown unit_state: " + wireId);
        };
    }
}
