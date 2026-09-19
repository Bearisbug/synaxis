package io.github.bearisbug.synaxis.game.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * The 10 Hz snapshot of everything in one player's interest set ({@code API-M2-006}).
 * <p>
 * It is a full overwrite, not a delta: every snapshot is therefore its own reconciliation and there
 * is no second path for correcting drift. Like {@link S2CUnitSpawn} it carries neither position nor
 * facing — only state and health — which is what keeps it inside the 256 byte budget of §15 for a
 * 50 unit view.
 *
 * @param serverTick the tick this snapshot describes, used by the client to align interpolation
 * @param entries    the units in the interest set, at most {@value #MAX_ENTRIES}; an entry whose
 *                   {@code unit_id} the client has not bound yet is ignored, not an error
 */
public record S2CUnitState(int serverTick, List<Entry> entries) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x82;

    /** The {@code count} cap of §9. */
    public static final int MAX_ENTRIES = 512;

    /**
     * One unit's line in a snapshot.
     *
     * @param unitId    the match scoped unit id
     * @param unitState what the unit is doing
     * @param hp        current health, {@code >= 0}
     */
    public record Entry(int unitId, UnitState unitState, int hp) {

        /**
         * Checks the id, the state and the health.
         */
        public Entry {
            if (unitId < 1) throw new MalformedMessageException("unit_id must be >= 1: " + unitId);
            if (unitState == null) throw new MalformedMessageException("unit_state is missing");
            if (hp < 0) throw new MalformedMessageException("hp must be >= 0: " + hp);
        }
    }

    /**
     * Copies the entries and checks the tick and the cap.
     */
    public S2CUnitState {
        entries = List.copyOf(entries);
        if (serverTick < 0) throw new MalformedMessageException("server_tick must be >= 0: " + serverTick);
        if (entries.size() > MAX_ENTRIES) {
            throw new MalformedMessageException(
                    "count %d is over the cap of %d".formatted(entries.size(), MAX_ENTRIES));
        }
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CUnitState read(MsgBuf buf) {
        final int serverTick = buf.readVarInt();
        final int count = buf.readCount(MAX_ENTRIES);
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readVarInt(), UnitState.fromWireId(buf.readUnsignedByte()), buf.readVarInt()));
        }
        return new S2CUnitState(serverTick, entries);
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(serverTick);
        buf.writeVarInt(entries.size());
        for (final Entry entry : entries) {
            buf.writeVarInt(entry.unitId());
            buf.writeUnsignedByte(entry.unitState().wireId());
            buf.writeVarInt(entry.hp());
        }
    }
}
