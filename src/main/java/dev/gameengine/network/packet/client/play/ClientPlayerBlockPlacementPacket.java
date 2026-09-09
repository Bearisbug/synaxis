package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.coordinate.BlockFace;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

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
