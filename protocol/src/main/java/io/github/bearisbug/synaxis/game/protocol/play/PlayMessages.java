package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

/**
 * The decoder of {@code synaxis:play} ({@code API-PLAY-002}).
 */
public final class PlayMessages {

    private PlayMessages() {
    }

    /**
     * Reads one envelope: the {@code msg_id} VarInt and then the matching body.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    @SuppressWarnings("DoNotCallSuggester") // a stub until the decoder is written
    public static PlayMessage read(MsgBuf buf) {
        throw new UnsupportedOperationException("the synaxis:play decoder is not written yet");
    }
}
