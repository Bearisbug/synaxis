package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BYTE;

public record HeldItemChangePacket(byte slot) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<HeldItemChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, HeldItemChangePacket::slot,
            HeldItemChangePacket::new);
}
