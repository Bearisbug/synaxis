package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.DOUBLE;

public record WorldBorderCenterPacket(double x, double z) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderCenterPacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderCenterPacket::x,
            DOUBLE, WorldBorderCenterPacket::z,
            WorldBorderCenterPacket::new);
}
