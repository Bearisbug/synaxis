package io.github.bearisbug.synaxis.protocol.play;

import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;

/**
 * The answer to exactly one {@link C2SAction} ({@code API-PLAY-002}): every action gets one, whichever check
 * of the host stopped it.
 *
 * @param actionSeq  the {@code action_seq} of the action being answered
 * @param code       the result code, a u8 kept as received; {@link #resultCode()} maps a value this version
 *                   does not know to {@link ActionResultCode#REJECTED}
 * @param reason     the reason to show, {@link EncodedComponent#NONE} when there is none
 * @param cooldownMs what is left of the cooldown, 0 to {@value PlayProtocol#MAX_COOLDOWN_MS}
 */
public record S2CActionResult(int actionSeq, int code, EncodedComponent reason, int cooldownMs) implements PlayMessage {

    /** The {@code msg_id} of this message on {@code synaxis:play}. */
    public static final int MSG_ID = 0x84;

    /**
     * Checks every field.
     */
    public S2CActionResult {
        if (actionSeq < 0) throw new MalformedMessageException("action_seq must be >= 0: " + actionSeq);
        PlayFields.checkU8("code", code);
        if (reason == null) throw new MalformedMessageException("reason is missing");
        PlayFields.checkCooldown(cooldownMs);
    }

    /**
     * A result with one of the codes this version knows.
     *
     * @param actionSeq  the {@code action_seq} of the action being answered
     * @param code       the result code
     * @param reason     the reason to show, {@link EncodedComponent#NONE} when there is none
     * @param cooldownMs what is left of the cooldown
     */
    public S2CActionResult(int actionSeq, ActionResultCode code, EncodedComponent reason, int cooldownMs) {
        this(actionSeq, code.wireId(), reason, cooldownMs);
    }

    /**
     * The code as the client shows it.
     *
     * @return the code, {@link ActionResultCode#REJECTED} for a value this version does not know
     */
    public ActionResultCode resultCode() {
        return ActionResultCode.fromWireId(code);
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link PlayMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CActionResult read(MsgBuf buf) {
        return new S2CActionResult(buf.readVarInt(), buf.readUnsignedByte(), EncodedComponent.read(buf), buf.readVarInt());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(actionSeq);
        buf.writeUnsignedByte(code);
        reason.write(buf);
        buf.writeVarInt(cooldownMs);
    }
}
