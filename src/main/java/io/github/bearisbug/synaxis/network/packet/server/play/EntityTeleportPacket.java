package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.intellij.lang.annotations.MagicConstant;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VECTOR3D;

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
