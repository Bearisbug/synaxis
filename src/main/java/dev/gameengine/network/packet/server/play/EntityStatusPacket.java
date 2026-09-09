package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.INT;

public record EntityStatusPacket(int entityId, byte status) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityStatusPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, EntityStatusPacket::entityId,
            BYTE, EntityStatusPacket::status,
            EntityStatusPacket::new);
}
