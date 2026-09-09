package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.DOUBLE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ProjectilePowerPacket(
        int entityId, double accelerationPower
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ProjectilePowerPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ProjectilePowerPacket::entityId,
            DOUBLE, ProjectilePowerPacket::accelerationPower,
            ProjectilePowerPacket::new);
}
