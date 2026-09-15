package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ClientCommandChatPacket(String message) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCommandChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ClientCommandChatPacket::message,
            ClientCommandChatPacket::new);

    public ClientCommandChatPacket {
        Check.argCondition(message.length() > 256, "Message length cannot be greater than 256");
    }
}
