package dev.gameengine.network.packet.client.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientFinishConfigurationPacket() implements ClientPacket.Configuration {
    public static final NetworkBuffer.Type<ClientFinishConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientFinishConfigurationPacket());
}
