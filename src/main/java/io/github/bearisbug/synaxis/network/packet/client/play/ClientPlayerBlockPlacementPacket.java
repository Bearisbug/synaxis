package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientPlayerBlockPlacementPacket(
        PlayerHand hand, Point blockPosition, BlockFace blockFace,
        float cursorPositionX, float cursorPositionY, float cursorPositionZ,
        boolean insideBlock, boolean hitWorldBorder, int sequence) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPlayerBlockPlacementPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(PlayerHand.class), ClientPlayerBlockPlacementPacket::hand,
            BLOCK_POSITION, ClientPlayerBlockPlacementPacket::blockPosition,
            Enum(BlockFace.class), ClientPlayerBlockPlacementPacket::blockFace,
            FLOAT, ClientPlayerBlockPlacementPacket::cursorPositionX,
            FLOAT, ClientPlayerBlockPlacementPacket::cursorPositionY,
            FLOAT, ClientPlayerBlockPlacementPacket::cursorPositionZ,
            BOOLEAN, ClientPlayerBlockPlacementPacket::insideBlock,
            BOOLEAN, ClientPlayerBlockPlacementPacket::hitWorldBorder,
            VAR_INT, ClientPlayerBlockPlacementPacket::sequence,
            ClientPlayerBlockPlacementPacket::new);
}
