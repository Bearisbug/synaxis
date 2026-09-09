package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.STRING;

public record ClientCommandChatPacket(String message) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCommandChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientCommandChatPacket::message,
            ClientCommandChatPacket::new);

    public ClientCommandChatPacket {
        Check.argCondition(message.length() > 256, "Message length cannot be greater than 256");
    }
}
