package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record StartConfigurationPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<StartConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new StartConfigurationPacket());
}
