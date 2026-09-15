package io.github.bearisbug.synaxis.network.packet.server.configuration;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record ResetChatPacket() implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<ResetChatPacket> SERIALIZER = NetworkBufferTemplate.template(new ResetChatPacket());
}
