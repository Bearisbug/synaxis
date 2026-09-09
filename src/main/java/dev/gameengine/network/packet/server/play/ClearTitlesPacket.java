package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;

public record ClearTitlesPacket(boolean reset) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ClearTitlesPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClearTitlesPacket::reset,
            ClearTitlesPacket::new);
}
