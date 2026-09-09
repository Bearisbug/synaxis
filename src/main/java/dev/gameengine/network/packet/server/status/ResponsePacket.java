package dev.gameengine.network.packet.server.status;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.STRING;

public record ResponsePacket(String jsonResponse) implements ServerPacket.Status {
    public static final NetworkBuffer.Type<ResponsePacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ResponsePacket::jsonResponse,
            ResponsePacket::new);
}
