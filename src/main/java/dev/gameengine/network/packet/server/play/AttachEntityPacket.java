package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.INT;

public record AttachEntityPacket(int attachedEntityId, int holdingEntityId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<AttachEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, AttachEntityPacket::attachedEntityId,
            INT, AttachEntityPacket::holdingEntityId,
            AttachEntityPacket::new);
}
