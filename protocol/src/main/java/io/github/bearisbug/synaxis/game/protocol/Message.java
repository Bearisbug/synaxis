package io.github.bearisbug.synaxis.game.protocol;

/**
 * One custom payload message of {@code API-M2-001}: the {@code msg_id} VarInt envelope followed by
 * the body.
 * <p>
 * {@code msg_id} is scoped to a single channel, so there is no global message number registry and
 * every channel is a closed set of its own, a sealed interface: {@link HandshakeMessage} and
 * {@link io.github.bearisbug.synaxis.game.protocol.play.PlayMessage}, the host's scene layer
 * {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}). A new
 * channel brings its own in a package of its own and leaves this interface as it is. Encoding is
 * symmetric per record — {@link #write(MsgBuf)} writes envelope and body, while the static
 * {@code read} of each record reads the body only, after the channel decoder has taken the id.
 */
public interface Message {

    /**
     * The id this message carries in its envelope, within its own channel.
     *
     * @return the {@code msg_id}
     */
    int msgId();

    /**
     * Writes {@code msg_id} and the body.
     *
     * @param buf the writing buffer to append to
     */
    void write(MsgBuf buf);

    /**
     * The payload of this message on its channel: {@code msg_id} and the body, as {@link #write(MsgBuf)}
     * writes them into a new buffer.
     *
     * @return the bytes to send
     */
    default byte[] encode() {
        final MsgBuf buf = MsgBuf.writing();
        write(buf);
        return buf.toByteArray();
    }
}
