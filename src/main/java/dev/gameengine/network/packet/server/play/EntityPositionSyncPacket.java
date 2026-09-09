package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;
import static dev.gameengine.network.NetworkBuffer.VECTOR3D;

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
