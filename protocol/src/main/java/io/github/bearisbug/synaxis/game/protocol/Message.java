package io.github.bearisbug.synaxis.game.protocol;

import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;

/**
 * One custom payload message of {@code API-M2-001}: the {@code msg_id} VarInt envelope followed by
 * the body.
 * <p>
 * {@code msg_id} is scoped to a single channel, so there is no global message number registry and
 * every channel is a closed set of its own: {@link HandshakeMessage}, {@link GameMessage} and
 * {@link PlayMessage}, the host's scene layer {@code synaxis:play} ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-002}). Encoding
 * is symmetric per record — {@link #write(MsgBuf)} writes envelope and body, while the static
 * {@code read} of each record reads the body only, after the channel decoder has taken the id.
 */
public sealed interface Message permits GameMessage, HandshakeMessage, PlayMessage {

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
