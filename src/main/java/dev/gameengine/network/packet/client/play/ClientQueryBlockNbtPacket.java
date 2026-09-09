package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BLOCK_POSITION;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientQueryBlockNbtPacket(int transactionId, Point blockPosition) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientQueryBlockNbtPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientQueryBlockNbtPacket::transactionId,
            BLOCK_POSITION, ClientQueryBlockNbtPacket::blockPosition,
            ClientQueryBlockNbtPacket::new);
}
