package io.github.bearisbug.synaxis.game.protocol;

import java.util.List;

/**
 * Units that left this player's interest set or were removed from the match ({@code API-M2-006}).
 * <p>
 * This is also how {@code removed} reaches the client: the snapshot never carries that state.
 *
 * @param unitIds the units to forget; the count shares the {@value S2CUnitState#MAX_ENTRIES} cap of
 *                the snapshot, since both are bounded by the same interest set
 */
public record S2CUnitDespawn(List<Integer> unitIds) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x83;

    /**
     * Copies the list and checks the cap and the ids.
     */
    public S2CUnitDespawn {
        unitIds = List.copyOf(unitIds);
        if (unitIds.size() > S2CUnitState.MAX_ENTRIES) {
            throw new MalformedMessageException(
                    "count %d is over the cap of %d".formatted(unitIds.size(), S2CUnitState.MAX_ENTRIES));
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
    public static S2CUnitDespawn read(MsgBuf buf) {
        return new S2CUnitDespawn(buf.readVarIntList(S2CUnitState.MAX_ENTRIES));
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
