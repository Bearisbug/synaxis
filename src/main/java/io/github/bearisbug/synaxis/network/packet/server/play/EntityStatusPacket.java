package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record EntityStatusPacket(int entityId, byte status) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityStatusPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, EntityStatusPacket::entityId,
            BYTE, EntityStatusPacket::status,
            EntityStatusPacket::new);
}
