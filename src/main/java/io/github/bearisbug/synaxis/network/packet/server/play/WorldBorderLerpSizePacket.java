package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_LONG;

public record WorldBorderLerpSizePacket(double oldDiameter, double newDiameter,
                                        long speed) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderLerpSizePacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderLerpSizePacket::oldDiameter,
            DOUBLE, WorldBorderLerpSizePacket::newDiameter,
            VAR_LONG, WorldBorderLerpSizePacket::speed,
            WorldBorderLerpSizePacket::new);
}
