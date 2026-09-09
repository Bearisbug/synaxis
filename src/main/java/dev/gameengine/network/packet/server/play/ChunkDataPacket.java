package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.packet.server.play.data.ChunkData;
import dev.gameengine.network.packet.server.play.data.LightData;

import static dev.gameengine.network.NetworkBuffer.INT;

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