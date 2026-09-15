package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;

public record KeepAlivePacket(long id) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<KeepAlivePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, KeepAlivePacket::id, KeepAlivePacket::new);
}
