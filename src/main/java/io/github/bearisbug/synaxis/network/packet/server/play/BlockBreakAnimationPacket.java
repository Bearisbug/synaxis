package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record BlockBreakAnimationPacket(int entityId, Point blockPosition,
                                        byte destroyStage) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BlockBreakAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, BlockBreakAnimationPacket::entityId,
            BLOCK_POSITION, BlockBreakAnimationPacket::blockPosition,
            BYTE, BlockBreakAnimationPacket::destroyStage,
            BlockBreakAnimationPacket::new);
}