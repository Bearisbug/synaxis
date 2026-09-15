package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record ChunkDataPacket(
        int chunkX, int chunkZ,
        ChunkData chunkData,
        LightData lightData
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ChunkDataPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, ChunkDataPacket::chunkX,
            INT, ChunkDataPacket::chunkZ,
            ChunkData.NETWORK_TYPE, ChunkDataPacket::chunkData,
            LightData.NETWORK_TYPE, ChunkDataPacket::lightData,
            ChunkDataPacket::new
    );
}