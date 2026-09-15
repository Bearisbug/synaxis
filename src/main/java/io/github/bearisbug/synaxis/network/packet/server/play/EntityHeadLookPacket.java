package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record EntityHeadLookPacket(int entityId, float yaw) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityHeadLookPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityHeadLookPacket::entityId,
            BYTE, value -> (byte) (value.yaw * 256f / 360f),
            (entityId, yaw) -> new EntityHeadLookPacket(entityId, yaw * 360f / 256f)
    );
}
