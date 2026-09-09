package dev.gameengine.network.packet.server.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record ResetChatPacket() implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<ResetChatPacket> SERIALIZER = NetworkBufferTemplate.template(new ResetChatPacket());
}
