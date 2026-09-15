package io.github.bearisbug.synaxis.network.packet.server.status;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ResponsePacket(String jsonResponse) implements ServerPacket.Status {
    public static final NetworkBuffer.Type<ResponsePacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, ResponsePacket::jsonResponse,
            ResponsePacket::new);
}
