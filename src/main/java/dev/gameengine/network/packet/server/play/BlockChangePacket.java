package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.instance.block.Block;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record BlockChangePacket(Point blockPosition, int blockStateId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BlockChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, BlockChangePacket::blockPosition,
            VAR_INT, BlockChangePacket::blockStateId,
            BlockChangePacket::new);

    public BlockChangePacket(Point blockPosition, Block block) {
        this(blockPosition, block.stateId());
    }
}
