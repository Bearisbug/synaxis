package dev.gameengine.network.packet.client.play;

import dev.gameengine.crypto.ChatSession;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientChatSessionUpdatePacket(ChatSession chatSession) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChatSessionUpdatePacket> SERIALIZER = NetworkBufferTemplate.template(
            ChatSession.SERIALIZER, ClientChatSessionUpdatePacket::chatSession,
            ClientChatSessionUpdatePacket::new
    );
}
