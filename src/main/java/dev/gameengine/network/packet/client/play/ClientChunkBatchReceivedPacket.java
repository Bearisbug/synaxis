package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.FLOAT;

public record ClientChunkBatchReceivedPacket(float targetChunksPerTick) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChunkBatchReceivedPacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, ClientChunkBatchReceivedPacket::targetChunksPerTick,
            ClientChunkBatchReceivedPacket::new);
}
