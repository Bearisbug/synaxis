package dev.gameengine.network.packet.server.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record TransferPacket(
        String host,
        int port
) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<TransferPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.STRING, TransferPacket::host,
            NetworkBuffer.VAR_INT, TransferPacket::port,
            TransferPacket::new);
}
