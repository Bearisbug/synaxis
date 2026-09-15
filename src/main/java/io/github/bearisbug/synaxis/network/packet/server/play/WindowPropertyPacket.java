package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record WindowPropertyPacket(int windowId, short property, short value) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WindowPropertyPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, WindowPropertyPacket::windowId,
            SHORT, WindowPropertyPacket::property,
            SHORT, WindowPropertyPacket::value,
            WindowPropertyPacket::new);
}
