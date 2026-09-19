package io.github.bearisbug.synaxis.game.protocol;

import java.util.List;

/**
 * The set of units the client has selected ({@code API-M2-004}), replace semantics.
 * <p>
 * The cap of eight is not a UI limit — M2 only ever single selects. It bounds the packet burst one
 * click can produce, which is what makes the per operation token bucket of {@code API-M2-005} add
 * up.
 *
 * @param unitIds the selected units, at most {@value #MAX_UNITS} of them; the server silently drops
 *                the ones the sender does not own instead of answering an error
 */
public record C2SSelect(List<Integer> unitIds) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x02;

    /** The {@code count} cap of §9; more than this is a protocol error, not a clamp. */
    public static final int MAX_UNITS = 8;

    /**
     * Copies the list and checks the cap and the ids.
     */
    public C2SSelect {
        unitIds = List.copyOf(unitIds);
        if (unitIds.size() > MAX_UNITS) {
            throw new MalformedMessageException(
                    "count %d is over the cap of %d".formatted(unitIds.size(), MAX_UNITS));
        }
        for (final int unitId : unitIds) {
            if (unitId < 1) throw new MalformedMessageException("unit_id must be >= 1: " + unitId);
        }
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SSelect read(MsgBuf buf) {
        return new C2SSelect(buf.readVarIntList(MAX_UNITS));
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarIntList(unitIds);
    }
}
