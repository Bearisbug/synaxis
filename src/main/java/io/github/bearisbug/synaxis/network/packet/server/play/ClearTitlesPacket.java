package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record ClearTitlesPacket(boolean reset) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ClearTitlesPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClearTitlesPacket::reset,
            ClearTitlesPacket::new);
}
