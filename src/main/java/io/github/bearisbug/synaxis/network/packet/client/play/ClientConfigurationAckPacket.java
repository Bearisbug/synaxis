package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientConfigurationAckPacket() implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientConfigurationAckPacket> SERIALIZER = NetworkBufferTemplate.template(new ClientConfigurationAckPacket());
}
