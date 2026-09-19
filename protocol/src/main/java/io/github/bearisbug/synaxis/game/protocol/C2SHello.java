package io.github.bearisbug.synaxis.game.protocol;

/**
 * The client mod announcing itself in the configuration phase ({@code API-M2-002}).
 * <p>
 * A connection sends exactly one; the host answers {@link S2CHello} when {@code schema_version}
 * equals its own and disconnects otherwise. {@code mod_version} never takes part in that decision,
 * it only reaches the logs.
 *
 * @param modVersion    the client mod build, {@code >= 1}
 * @param schemaVersion the wire schema the client speaks, {@code >= 1}
 */
public record C2SHello(int modVersion, int schemaVersion) implements HandshakeMessage {

    /** The {@code msg_id} of this message on the {@code synaxis:handshake} channel. */
    public static final int MSG_ID = 0x01;

    /**
     * Checks both version axes.
     */
    public C2SHello {
        if (modVersion < 1) throw new MalformedMessageException("mod_version must be >= 1: " + modVersion);
        if (schemaVersion < 1) {
            throw new MalformedMessageException("schema_version must be >= 1: " + schemaVersion);
        }
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link HandshakeMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SHello read(MsgBuf buf) {
        return new C2SHello(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(modVersion);
        buf.writeVarInt(schemaVersion);
    }
}
