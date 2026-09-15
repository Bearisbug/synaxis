package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record CloseWindowPacket(int windowId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<CloseWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, CloseWindowPacket::windowId,
            CloseWindowPacket::new);
}
