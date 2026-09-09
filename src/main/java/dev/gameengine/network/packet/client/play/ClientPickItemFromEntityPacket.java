package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientPickItemFromEntityPacket(int entityId, boolean includeData) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPickItemFromEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientPickItemFromEntityPacket::entityId,
            BOOLEAN, ClientPickItemFromEntityPacket::includeData,
            ClientPickItemFromEntityPacket::new);
}
