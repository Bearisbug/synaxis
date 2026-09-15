package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VECTOR3D;

public record EntityPositionSyncPacket(
        int entityId, Point position, Point delta,
        float yaw, float pitch, boolean onGround
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityPositionSyncPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityPositionSyncPacket::entityId,
            VECTOR3D, EntityPositionSyncPacket::position,
            VECTOR3D, EntityPositionSyncPacket::delta,
            FLOAT, EntityPositionSyncPacket::yaw,
            FLOAT, EntityPositionSyncPacket::pitch,
            BOOLEAN, EntityPositionSyncPacket::onGround,
            EntityPositionSyncPacket::new);
}
