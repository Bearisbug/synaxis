package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.instance.block.Block;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record BlockActionPacket(Point blockPosition, byte actionId,
                                byte actionParam, int blockId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BlockActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, BlockActionPacket::blockPosition,
            BYTE, BlockActionPacket::actionId,
            BYTE, BlockActionPacket::actionParam,
            VAR_INT, BlockActionPacket::blockId,
            BlockActionPacket::new);

    public BlockActionPacket(Point blockPosition, byte actionId, byte actionParam, Block block) {
        this(blockPosition, actionId, actionParam, block.id());
    }
}
