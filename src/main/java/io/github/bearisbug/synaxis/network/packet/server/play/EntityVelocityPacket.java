package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record EntityVelocityPacket(int entityId, Vec velocity) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityVelocityPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, EntityVelocityPacket::entityId,
            NetworkBuffer.LP_VECTOR3, EntityVelocityPacket::velocity,
            EntityVelocityPacket::new);
}
