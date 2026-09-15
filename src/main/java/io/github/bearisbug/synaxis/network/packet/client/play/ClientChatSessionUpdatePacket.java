package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.crypto.ChatSession;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientChatSessionUpdatePacket(ChatSession chatSession) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChatSessionUpdatePacket> SERIALIZER = NetworkBufferTemplate.template(
            ChatSession.SERIALIZER, ClientChatSessionUpdatePacket::chatSession,
            ClientChatSessionUpdatePacket::new
    );
}
