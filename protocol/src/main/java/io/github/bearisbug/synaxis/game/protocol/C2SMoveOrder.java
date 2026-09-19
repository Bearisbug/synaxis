package io.github.bearisbug.synaxis.game.protocol;

/**
 * One move order ({@code API-M2-005}). Every one of them is answered by a {@link S2COrderResult}.
 * <p>
 * It carries a single {@code unit_id} on purpose: the answer has one {@code code} and one snapped
 * point, so a multi unit order would have no way to say "partly accepted". A client that has eight
 * units selected sends eight orders sharing one {@code order_group}, and the server's token bucket
 * counts groups rather than packets.
 *
 * @param orderSeq   per connection, strictly increasing; anything not greater than the largest one
 *                   already accepted comes back as {@link OrderResultCode#STALE}
 * @param orderGroup the click this order belongs to, per connection non decreasing; in M2 it equals
 *                   {@code orderSeq}, and it exists now so that multi select in M3 is not a
 *                   breaking layout change
 * @param unitId     the unit to move
 * @param targetX    the world X that was clicked
 * @param targetY    the world Y that was clicked
 * @param targetZ    the world Z that was clicked
 * @param mods       a u8 of modifier bits; bit 0 (queue) is ignored in M2 and must be written as 0
 */
public record C2SMoveOrder(int orderSeq, int orderGroup, int unitId,
                           float targetX, float targetY, float targetZ, int mods) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x03;

    /**
     * Checks the two sequence numbers, the unit id and the modifier byte.
     */
    public C2SMoveOrder {
        if (orderSeq < 0) throw new MalformedMessageException("order_seq must be >= 0: " + orderSeq);
        if (orderGroup < 0) throw new MalformedMessageException("order_group must be >= 0: " + orderGroup);
        if (unitId < 1) throw new MalformedMessageException("unit_id must be >= 1: " + unitId);
        if ((mods & ~0xFF) != 0) throw new MalformedMessageException("mods is not a u8: " + mods);
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SMoveOrder read(MsgBuf buf) {
        return new C2SMoveOrder(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readUnsignedByte());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(orderSeq);
        buf.writeVarInt(orderGroup);
        buf.writeVarInt(unitId);
        buf.writeFloat(targetX);
        buf.writeFloat(targetY);
        buf.writeFloat(targetZ);
        buf.writeUnsignedByte(mods);
    }
}
