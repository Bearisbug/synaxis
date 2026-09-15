package io.github.bearisbug.synaxis.network.packet.client.configuration;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientAcceptCodeOfConductPacket() implements ClientPacket.Configuration {
    public static final NetworkBuffer.Type<ClientAcceptCodeOfConductPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientAcceptCodeOfConductPacket());
}
