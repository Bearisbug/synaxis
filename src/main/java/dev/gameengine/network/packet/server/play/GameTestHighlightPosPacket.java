package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record GameTestHighlightPosPacket(
        Point absoluteBlockPosition,
        Point relativeBlockPosition
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<GameTestHighlightPosPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.BLOCK_POSITION, GameTestHighlightPosPacket::absoluteBlockPosition,
            NetworkBuffer.BLOCK_POSITION, GameTestHighlightPosPacket::relativeBlockPosition,
            GameTestHighlightPosPacket::new);
}
