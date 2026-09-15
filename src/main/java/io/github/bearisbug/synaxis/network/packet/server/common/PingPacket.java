package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record PingPacket(int id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<PingPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, PingPacket::id, PingPacket::new);
}
