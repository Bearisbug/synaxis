package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.SHORT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record WindowPropertyPacket(int windowId, short property, short value) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WindowPropertyPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, WindowPropertyPacket::windowId,
            SHORT, WindowPropertyPacket::property,
            SHORT, WindowPropertyPacket::value,
            WindowPropertyPacket::new);
}
