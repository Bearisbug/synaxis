package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ChunkBatchFinishedPacket(int batchSize) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ChunkBatchFinishedPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ChunkBatchFinishedPacket::batchSize,
            ChunkBatchFinishedPacket::new);
}
