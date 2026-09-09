package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record ChunkBatchStartPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ChunkBatchStartPacket> SERIALIZER = NetworkBufferTemplate.template(new ChunkBatchStartPacket());
}
