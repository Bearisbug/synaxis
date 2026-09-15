package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;

public record PingResponsePacket(long number) implements ServerPacket.Status, ServerPacket.Play {
    public static final NetworkBuffer.Type<PingResponsePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, PingResponsePacket::number,
            PingResponsePacket::new);
}
