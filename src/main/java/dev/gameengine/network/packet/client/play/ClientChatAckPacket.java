package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientChatAckPacket(int offset) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChatAckPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientChatAckPacket::offset,
            ClientChatAckPacket::new);
}
