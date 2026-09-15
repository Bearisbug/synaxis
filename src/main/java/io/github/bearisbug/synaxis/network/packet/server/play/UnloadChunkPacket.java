package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record UnloadChunkPacket(int chunkX, int chunkZ) implements ServerPacket.Play {
    // Client reads this as a single long in big endian, so we have to write it backwards
    public static final NetworkBuffer.Type<UnloadChunkPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, UnloadChunkPacket::chunkZ,
            INT, UnloadChunkPacket::chunkX,
            (z, x) -> new UnloadChunkPacket(x, z)
    );
}
