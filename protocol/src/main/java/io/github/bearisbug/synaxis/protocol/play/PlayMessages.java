package io.github.bearisbug.synaxis.protocol.play;

import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.UnknownMessageException;

/**
 * The decoder of {@code synaxis:play} ({@code API-PLAY-002}).
 * <p>
 * {@code msg_id} is scoped to this channel. The ids version 1 does not define, {@code S2C_GRANTS} of W1 and
 * {@code S2C_NOTICE} and {@code S2C_SCENE_LEAVE} of W2 among them, are unknown rather than malformed: the
 * receiver drops them with a warning and keeps the connection, so a newer server does not disconnect an older
 * client.
 */
public final class PlayMessages {

    private PlayMessages() {
    }

    /**
     * Reads one envelope: the {@code msg_id} VarInt and then the matching body.
     * <p>
     * Bytes left after the body are the caller's business: a decoder reads the fields it knows and skips
     * whatever a newer sender appended ({@code docs/DESIGN-M2.md} §8).
     *
     * @param buf the reading buffer
     * @return the decoded message
     * @throws UnknownMessageException   if this channel defines no such {@code msg_id}
     * @throws MalformedMessageException if the payload cannot be decoded
     */
    public static PlayMessage read(MsgBuf buf) {
        final int msgId = buf.readVarInt();
        return switch (msgId) {
            case S2CScene.MSG_ID -> S2CScene.read(buf);
            case S2CHud.MSG_ID -> S2CHud.read(buf);
            case S2CActionResult.MSG_ID -> S2CActionResult.read(buf);
            case C2SSceneReady.MSG_ID -> C2SSceneReady.read(buf);
            case C2SAction.MSG_ID -> C2SAction.read(buf);
            default -> throw new UnknownMessageException(PlayProtocol.CHANNEL, msgId);
        };
    }
}
