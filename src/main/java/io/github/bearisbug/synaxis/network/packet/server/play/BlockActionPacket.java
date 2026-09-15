package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

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
