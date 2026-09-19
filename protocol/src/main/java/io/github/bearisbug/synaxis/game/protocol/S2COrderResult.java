package io.github.bearisbug.synaxis.game.protocol;

/**
 * The answer to exactly one {@link C2SMoveOrder} ({@code API-M2-005}).
 * <p>
 * Every order gets one, whether the unit was idle or already moving: this is the only feedback path
 * for "I clicked the ground and nothing happened", so a silent drop is never an option — not even
 * when the order was rate limited.
 *
 * @param orderSeq  the {@code order_seq} of the order being answered
 * @param code      why the order was accepted or refused
 * @param snappedX  the X the unit will actually walk to when {@code code} is {@link OrderResultCode#OK},
 *                  otherwise the requested value echoed back
 * @param snappedY  the Y under the same rule
 * @param snappedZ  the Z under the same rule
 */
public record S2COrderResult(int orderSeq, OrderResultCode code,
                             float snappedX, float snappedY, float snappedZ) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x84;

    /**
     * Checks the sequence number and that a code is present.
     */
    public S2COrderResult {
        if (orderSeq < 0) throw new MalformedMessageException("order_seq must be >= 0: " + orderSeq);
        if (code == null) throw new MalformedMessageException("order_result_code is missing");
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2COrderResult read(MsgBuf buf) {
        return new S2COrderResult(buf.readVarInt(), OrderResultCode.fromWireId(buf.readUnsignedByte()),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(orderSeq);
        buf.writeUnsignedByte(code.wireId());
        buf.writeFloat(snappedX);
        buf.writeFloat(snappedY);
        buf.writeFloat(snappedZ);
    }
}
