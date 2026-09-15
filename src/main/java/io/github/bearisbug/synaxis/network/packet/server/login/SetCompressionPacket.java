package io.github.bearisbug.synaxis.network.packet.server.login;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SetCompressionPacket(int threshold) implements ServerPacket.Login {
    public static final NetworkBuffer.Type<SetCompressionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SetCompressionPacket::threshold,
            SetCompressionPacket::new);
}
