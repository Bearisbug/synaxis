package io.github.bearisbug.synaxis.network.packet.server.configuration;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record FinishConfigurationPacket() implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<FinishConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new FinishConfigurationPacket());
}
