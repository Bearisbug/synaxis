package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.LONG;

public record KeepAlivePacket(long id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<KeepAlivePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, KeepAlivePacket::id, KeepAlivePacket::new);
}
