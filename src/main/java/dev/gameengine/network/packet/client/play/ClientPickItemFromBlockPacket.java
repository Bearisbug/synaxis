package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.BOOLEAN;

public record ClientPickItemFromBlockPacket(Point pos, boolean includeData) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPickItemFromBlockPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientPickItemFromBlockPacket::pos,
            BOOLEAN, ClientPickItemFromBlockPacket::includeData,
            ClientPickItemFromBlockPacket::new);
}
