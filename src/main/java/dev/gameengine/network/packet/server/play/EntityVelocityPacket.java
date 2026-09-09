package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Vec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record EntityVelocityPacket(int entityId, Vec velocity) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityVelocityPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, EntityVelocityPacket::entityId,
            NetworkBuffer.LP_VECTOR3, EntityVelocityPacket::velocity,
            EntityVelocityPacket::new);
}
