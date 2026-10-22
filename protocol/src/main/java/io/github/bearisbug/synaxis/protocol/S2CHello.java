package io.github.bearisbug.synaxis.protocol;

/**
 * The host letting a verified client through ({@code API-M2-002}).
 *
 * @param schemaVersion the wire schema the host speaks, {@code >= 1} and equal to the client's
 * @param tickRate      ticks per second, a u8; 20 unless the host sets {@code synaxis.tps}
 * @param serverTick    the tick this answer was written on
 */
public record S2CHello(int schemaVersion, int tickRate, int serverTick) implements HandshakeMessage {

    /** The {@code msg_id} of this message on the {@code synaxis:handshake} channel. */
    public static final int MSG_ID = 0x81;

    /**
     * Checks the schema axis and the two numeric ranges.
     */
    public S2CHello {
        if (schemaVersion < 1) {
            throw new MalformedMessageException("schema_version must be >= 1: " + schemaVersion);
        }
        if ((tickRate & ~0xFF) != 0) throw new MalformedMessageException("tick_rate is not a u8: " + tickRate);
        if (serverTick < 0) throw new MalformedMessageException("server_tick must be >= 0: " + serverTick);
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link HandshakeMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CHello read(MsgBuf buf) {
        return new S2CHello(buf.readVarInt(), buf.readUnsignedByte(), buf.readVarInt());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(schemaVersion);
        buf.writeUnsignedByte(tickRate);
        buf.writeVarInt(serverTick);
    }
}
