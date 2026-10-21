package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record DestroyEntitiesPacket(List<Integer> entityIds) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<DestroyEntitiesPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT.list(), DestroyEntitiesPacket::entityIds,
            DestroyEntitiesPacket::new);

    public DestroyEntitiesPacket {
        entityIds = List.copyOf(entityIds);
    }

    public DestroyEntitiesPacket(int entityId) {
        this(List.of(entityId));
    }
}
