package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.LONG;

public record PingResponsePacket(long number) implements ServerPacket.Status, ServerPacket.Play {
    public static final NetworkBuffer.Type<PingResponsePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, PingResponsePacket::number,
            PingResponsePacket::new);
}
