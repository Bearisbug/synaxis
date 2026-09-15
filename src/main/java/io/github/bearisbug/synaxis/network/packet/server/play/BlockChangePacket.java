package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record BlockChangePacket(Point blockPosition, int blockStateId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<BlockChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, BlockChangePacket::blockPosition,
            VAR_INT, BlockChangePacket::blockStateId,
            BlockChangePacket::new);

    public BlockChangePacket(Point blockPosition, Block block) {
        this(blockPosition, block.stateId());
    }
}
