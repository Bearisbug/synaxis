package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record TransferPacket(
        String host,
        int port
) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<TransferPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.STRING, TransferPacket::host,
            NetworkBuffer.VAR_INT, TransferPacket::port,
            TransferPacket::new);
}
