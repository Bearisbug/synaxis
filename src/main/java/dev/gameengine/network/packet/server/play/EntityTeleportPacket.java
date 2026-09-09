package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.RelativeFlags;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import org.intellij.lang.annotations.MagicConstant;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.INT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;
import static dev.gameengine.network.NetworkBuffer.VECTOR3D;

public record EntityTeleportPacket(
        int entityId, Pos position, Point delta,
        @MagicConstant(flagsFromClass = RelativeFlags.class) int flags,
        boolean onGround) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityTeleportPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityTeleportPacket::entityId,
            VECTOR3D, EntityTeleportPacket::position,
            VECTOR3D, EntityTeleportPacket::delta,
            FLOAT, value -> value.position.yaw(),
            FLOAT, value -> value.position.pitch(),
            INT, EntityTeleportPacket::flags,
            BOOLEAN, EntityTeleportPacket::onGround,
            (entityId, absPosition, deltaMovement, yaw, pitch, flags, onGround) ->
                    new EntityTeleportPacket(entityId, absPosition.asPos().withView(yaw, pitch),
                            deltaMovement, flags, onGround)
    );
}
