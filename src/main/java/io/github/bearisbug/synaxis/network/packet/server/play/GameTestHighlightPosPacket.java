package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record GameTestHighlightPosPacket(
        Point absoluteBlockPosition,
        Point relativeBlockPosition
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<GameTestHighlightPosPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.BLOCK_POSITION, GameTestHighlightPosPacket::absoluteBlockPosition,
            NetworkBuffer.BLOCK_POSITION, GameTestHighlightPosPacket::relativeBlockPosition,
            GameTestHighlightPosPacket::new);
}
