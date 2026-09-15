package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_LONG;

public record InitializeWorldBorderPacket(double x, double z,
                                          double oldDiameter, double newDiameter, long speed,
                                          int portalTeleportBoundary, int warningTime,
                                          int warningBlocks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<InitializeWorldBorderPacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, InitializeWorldBorderPacket::x,
            DOUBLE, InitializeWorldBorderPacket::z,
            DOUBLE, InitializeWorldBorderPacket::oldDiameter,
            DOUBLE, InitializeWorldBorderPacket::newDiameter,
            VAR_LONG, InitializeWorldBorderPacket::speed,
            VAR_INT, InitializeWorldBorderPacket::portalTeleportBoundary,
            VAR_INT, InitializeWorldBorderPacket::warningTime,
            VAR_INT, InitializeWorldBorderPacket::warningBlocks,
            InitializeWorldBorderPacket::new);
}
