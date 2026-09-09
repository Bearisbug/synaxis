package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.DOUBLE;

public record WorldBorderSizePacket(double diameter) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<WorldBorderSizePacket> SERIALIZER = NetworkBufferTemplate.template(
            DOUBLE, WorldBorderSizePacket::diameter,
            WorldBorderSizePacket::new);
}
