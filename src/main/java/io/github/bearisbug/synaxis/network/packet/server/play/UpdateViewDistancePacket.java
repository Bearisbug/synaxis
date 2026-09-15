package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record UpdateViewDistancePacket(int viewDistance) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateViewDistancePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateViewDistancePacket::viewDistance,
            UpdateViewDistancePacket::new);
}
