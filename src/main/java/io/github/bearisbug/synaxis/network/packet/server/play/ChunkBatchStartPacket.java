package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record ChunkBatchStartPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ChunkBatchStartPacket> SERIALIZER = NetworkBufferTemplate.template(new ChunkBatchStartPacket());
}
