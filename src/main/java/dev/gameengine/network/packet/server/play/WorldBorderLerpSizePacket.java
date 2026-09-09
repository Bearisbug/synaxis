package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.DOUBLE;
import static dev.gameengine.network.NetworkBuffer.VAR_LONG;

public record WorldBorderLerpSizePacket(double oldDiameter, double newDiameter,
                                        long speed) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderLerpSizePacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderLerpSizePacket::oldDiameter,
            DOUBLE, WorldBorderLerpSizePacket::newDiameter,
            VAR_LONG, WorldBorderLerpSizePacket::speed,
            WorldBorderLerpSizePacket::new);
}
