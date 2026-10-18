package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

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

    /**
     * Builds the packet moving an entity from {@code oldPosition} to {@code newPosition}.
     * <p>
     * The caller makes sure the move is shorter than 8 blocks on every axis, see
     * {@link CoordConversion#deltaShort4096(double, double)}; a longer one is sent as an {@link EntityTeleportPacket}.
     *
     * @param entityId    the id of the entity moving
     * @param newPosition the position moved to
     * @param oldPosition the position the viewers last saw the entity at
     * @param onGround    whether the entity is on the ground
     * @return the packet
     */
    public static EntityPositionAndRotationPacket getPacket(int entityId,
                                                            Pos newPosition, Pos oldPosition,
                                                            boolean onGround) {
        final short deltaX = CoordConversion.deltaShort4096(newPosition.x(), oldPosition.x());
        final short deltaY = CoordConversion.deltaShort4096(newPosition.y(), oldPosition.y());
        final short deltaZ = CoordConversion.deltaShort4096(newPosition.z(), oldPosition.z());
        return new EntityPositionAndRotationPacket(entityId, deltaX, deltaY, deltaZ, newPosition.yaw(), newPosition.pitch(), onGround);
    }
}
