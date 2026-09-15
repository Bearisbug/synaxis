package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ProjectilePowerPacket(
        int entityId, double accelerationPower
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ProjectilePowerPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ProjectilePowerPacket::entityId,
            DOUBLE, ProjectilePowerPacket::accelerationPower,
            ProjectilePowerPacket::new);
}
