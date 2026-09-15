package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ChunkBatchFinishedPacket(int batchSize) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ChunkBatchFinishedPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ChunkBatchFinishedPacket::batchSize,
            ChunkBatchFinishedPacket::new);
}
