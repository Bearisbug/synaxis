package dev.gameengine.network.packet.server.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record FinishConfigurationPacket() implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<FinishConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new FinishConfigurationPacket());
}
