package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;

public record HeldItemChangePacket(byte slot) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<HeldItemChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            BYTE, HeldItemChangePacket::slot,
            HeldItemChangePacket::new);
}
