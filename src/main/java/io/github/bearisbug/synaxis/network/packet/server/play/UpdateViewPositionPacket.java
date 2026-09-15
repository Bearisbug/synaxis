package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record UpdateViewPositionPacket(int chunkX, int chunkZ) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateViewPositionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateViewPositionPacket::chunkX,
            VAR_INT, UpdateViewPositionPacket::chunkZ,
            UpdateViewPositionPacket::new);
}
