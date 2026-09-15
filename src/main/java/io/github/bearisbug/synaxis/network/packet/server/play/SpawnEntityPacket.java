package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.UUID;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LP_VECTOR3;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.UUID;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SpawnEntityPacket(
        int entityId, UUID uuid, EntityType type,
        Pos position, float headRot, int data,
        Vec velocity
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SpawnEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SpawnEntityPacket::entityId,
            UUID, SpawnEntityPacket::uuid,
            EntityType.NETWORK_TYPE, SpawnEntityPacket::type,
            DOUBLE, value -> value.position.x(),
            DOUBLE, value -> value.position.y(),
            DOUBLE, value -> value.position.z(),
            LP_VECTOR3, SpawnEntityPacket::velocity,
            BYTE, value -> (byte) (value.position.pitch() * 256f / 360f),
            BYTE, value -> (byte) (value.position.yaw() * 256f / 360f),
            BYTE, value -> (byte) (value.headRot * 256f / 360f),
            VAR_INT, SpawnEntityPacket::data,
            (entityId, uuid, type, x, y, z, velocity, pitch, yaw, headRot, data) ->
                    new SpawnEntityPacket(entityId, uuid, type,
                            new Pos(x, y, z, yaw * 360f / 256f, pitch * 360f / 256f),
                            headRot * 360f / 256f, data, velocity)
    );
}
