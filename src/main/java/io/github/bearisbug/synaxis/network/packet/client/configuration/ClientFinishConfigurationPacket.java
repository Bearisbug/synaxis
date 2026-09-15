package io.github.bearisbug.synaxis.network.packet.client.configuration;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientFinishConfigurationPacket() implements ClientPacket.Configuration {
    public static final NetworkBuffer.Type<ClientFinishConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientFinishConfigurationPacket());
}
