package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientCloseWindowPacket(int windowId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCloseWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientCloseWindowPacket::windowId,
            ClientCloseWindowPacket::new);
}
