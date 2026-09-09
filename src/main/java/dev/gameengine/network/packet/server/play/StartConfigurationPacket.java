package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record StartConfigurationPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<StartConfigurationPacket> SERIALIZER = NetworkBufferTemplate.template(new StartConfigurationPacket());
}
