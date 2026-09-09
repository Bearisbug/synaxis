package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientConfigurationAckPacket() implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientConfigurationAckPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientConfigurationAckPacket());
}
