package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;

public record WorldBorderSizePacket(double diameter) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderSizePacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderSizePacket::diameter,
            WorldBorderSizePacket::new);
}
