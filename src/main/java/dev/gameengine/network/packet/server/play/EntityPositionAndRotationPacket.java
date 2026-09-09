package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.CoordConversion;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.SHORT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record EntityPositionAndRotationPacket(int entityId, short deltaX, short deltaY, short deltaZ,
                                              float yaw, float pitch, boolean onGround) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityPositionAndRotationPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityPositionAndRotationPacket::entityId,
            SHORT, EntityPositionAndRotationPacket::deltaX,
            SHORT, EntityPositionAndRotationPacket::deltaY,
            SHORT, EntityPositionAndRotationPacket::deltaZ,
            BYTE, value -> (byte) (value.yaw * 256f / 360f),
            BYTE, value -> (byte) (value.pitch * 256f / 360f),
            BOOLEAN, EntityPositionAndRotationPacket::onGround,
            (entityId, deltaX, deltaY, deltaZ, yaw, pitch, onGround) -> new EntityPositionAndRotationPacket(
                    entityId, deltaX, deltaY, deltaZ,
                    yaw * 360f / 256f, pitch * 360f / 256f, onGround)
    );

    public static EntityPositionAndRotationPacket getPacket(int entityId,
                                                            Pos newPosition, Pos oldPosition,
                                                            boolean onGround) {
        final short deltaX = CoordConversion.deltaShort4096(newPosition.x(), oldPosition.x());
        final short deltaY = CoordConversion.deltaShort4096(newPosition.y(), oldPosition.y());
        final short deltaZ = CoordConversion.deltaShort4096(newPosition.z(), oldPosition.z());
        return new EntityPositionAndRotationPacket(entityId, deltaX, deltaY, deltaZ, newPosition.yaw(), newPosition.pitch(), onGround);
    }
}
