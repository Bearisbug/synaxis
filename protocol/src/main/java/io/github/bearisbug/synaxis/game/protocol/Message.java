package io.github.bearisbug.synaxis.game.protocol;

/**
 * One custom payload message of {@code API-M2-001}: the {@code msg_id} VarInt envelope followed by
 * the body.
 * <p>
 * {@code msg_id} is scoped to a single channel, so there is no global message number registry and
 * the two channels are two closed sets: {@link HandshakeMessage} and {@link GameMessage}. Encoding
 * is symmetric per record — {@link #write(MsgBuf)} writes envelope and body, while the static
 * {@code read} of each record reads the body only, after the channel decoder has taken the id.
 */
public sealed interface Message permits GameMessage, HandshakeMessage {

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
}
