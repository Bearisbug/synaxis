package dev.gameengine.network.packet.client.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientAcceptCodeOfConductPacket() implements ClientPacket.Configuration {
    public static final NetworkBuffer.Type<ClientAcceptCodeOfConductPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientAcceptCodeOfConductPacket());
}
