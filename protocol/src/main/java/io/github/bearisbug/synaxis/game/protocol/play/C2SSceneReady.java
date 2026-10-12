package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

/**
 * The client saying a scene is ready ({@code API-PLAY-002}): at most one per {@link S2CScene}, sent once the
 * scene is applied, no world loading screen is open and the chunk under the player has arrived (§6.4.3). The
 * host only records the one whose {@code scene_seq} is the current scene's.
 *
 * @param sceneSeq the {@code scene_seq} of the scene that is ready, from 1
 */
public record C2SSceneReady(int sceneSeq) implements PlayMessage {

    /** The {@code msg_id} of this message on {@code synaxis:play}. */
    public static final int MSG_ID = 0x01;

    /**
     * Checks the scene number.
     */
    public C2SSceneReady {
        if (sceneSeq < 1) throw new MalformedMessageException("scene_seq must be >= 1: " + sceneSeq);
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link PlayMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SSceneReady read(MsgBuf buf) {
        return new C2SSceneReady(buf.readVarInt());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(sceneSeq);
    }
}
