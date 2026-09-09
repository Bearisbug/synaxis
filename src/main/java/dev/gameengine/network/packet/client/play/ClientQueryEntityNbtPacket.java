package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientQueryEntityNbtPacket(int transactionId, int entityId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientQueryEntityNbtPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientQueryEntityNbtPacket::transactionId,
            VAR_INT, ClientQueryEntityNbtPacket::entityId,
            ClientQueryEntityNbtPacket::new);
}
