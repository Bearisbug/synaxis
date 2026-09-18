package io.github.bearisbug.synaxis.game.protocol;

/**
 * The decoder of the RTS slice channel ({@code API-M2-003} to {@code API-M2-006}).
 * <p>
 * {@code msg_id} is scoped to one channel, so these ids are free to collide with the handshake's:
 * the channel a payload arrived on decides which decoder reads it.
 */
public final class GameMessages {

    private static final String CHANNEL = "game";

    private GameMessages() {
    }

    /**
     * Reads one envelope: the {@code msg_id} VarInt and then the matching body.
     * <p>
     * Bytes left after the body are the caller's business — §8 says a decoder reads the fields it
     * knows and skips whatever a newer sender appended.
     *
     * @param buf the reading buffer
     * @return the decoded message
     * @throws UnknownMessageException   if this channel defines no such {@code msg_id}; the receiver
     *                                   drops the packet and warns, it does not disconnect
     * @throws MalformedMessageException if the payload cannot be decoded
     */
    public static GameMessage read(MsgBuf buf) {
        final int msgId = buf.readVarInt();
        return switch (msgId) {
            case C2SCamera.MSG_ID -> C2SCamera.read(buf);
            case C2SSelect.MSG_ID -> C2SSelect.read(buf);
            case C2SMoveOrder.MSG_ID -> C2SMoveOrder.read(buf);
            case S2CUnitSpawn.MSG_ID -> S2CUnitSpawn.read(buf);
            case S2CUnitState.MSG_ID -> S2CUnitState.read(buf);
            case S2CUnitDespawn.MSG_ID -> S2CUnitDespawn.read(buf);
            case S2COrderResult.MSG_ID -> S2COrderResult.read(buf);
            default -> throw new UnknownMessageException(CHANNEL, msgId);
        };
    }
}
