package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record AttachEntityPacket(int attachedEntityId, int holdingEntityId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<AttachEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, AttachEntityPacket::attachedEntityId,
            INT, AttachEntityPacket::holdingEntityId,
            AttachEntityPacket::new);
}
