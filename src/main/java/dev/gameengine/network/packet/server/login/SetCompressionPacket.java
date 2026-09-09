package dev.gameengine.network.packet.server.login;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record SetCompressionPacket(int threshold) implements ServerPacket.Login {
    public static final NetworkBuffer.Type<SetCompressionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SetCompressionPacket::threshold,
            SetCompressionPacket::new);
}
