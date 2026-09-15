package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;

public record WorldBorderCenterPacket(double x, double z) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderCenterPacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderCenterPacket::x,
            DOUBLE, WorldBorderCenterPacket::z,
            WorldBorderCenterPacket::new);
}
