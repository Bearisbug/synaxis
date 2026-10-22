package io.github.bearisbug.synaxis.protocol;

/**
 * The decoder of the {@code synaxis:handshake} channel ({@code API-M2-002}).
 */
public final class HandshakeMessages {

    private static final String CHANNEL = "handshake";

    private HandshakeMessages() {
    }

    /**
     * Reads one envelope: the {@code msg_id} VarInt and then the matching body.
     * <p>
     * Bytes left after the body are the caller's business — §8 says a decoder reads the fields it
     * knows and skips whatever a newer sender appended.
     *
     * @param buf the reading buffer
     * @return the decoded message
     * @throws UnknownMessageException   if this channel defines no such {@code msg_id}
     * @throws MalformedMessageException if the payload cannot be decoded
     */
    public static HandshakeMessage read(MsgBuf buf) {
        final int msgId = buf.readVarInt();
        return switch (msgId) {
            case C2SHello.MSG_ID -> C2SHello.read(buf);
            case S2CHello.MSG_ID -> S2CHello.read(buf);
            default -> throw new UnknownMessageException(CHANNEL, msgId);
        };
    }
}
