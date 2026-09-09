package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.INT;

public record PingPacket(int id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<PingPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, PingPacket::id, PingPacket::new);
}
