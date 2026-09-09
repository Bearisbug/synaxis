package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record BlockBreakAnimationPacket(int entityId, Point blockPosition,
                                        byte destroyStage) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BlockBreakAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, BlockBreakAnimationPacket::entityId,
            BLOCK_POSITION, BlockBreakAnimationPacket::blockPosition,
            BYTE, BlockBreakAnimationPacket::destroyStage,
            BlockBreakAnimationPacket::new);
}