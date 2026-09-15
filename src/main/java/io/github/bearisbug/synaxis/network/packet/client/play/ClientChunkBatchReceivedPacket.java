package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;

public record ClientChunkBatchReceivedPacket(float targetChunksPerTick) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientChunkBatchReceivedPacket> SERIALIZER = NetworkBufferTemplate.template(
            FLOAT, ClientChunkBatchReceivedPacket::targetChunksPerTick,
            ClientChunkBatchReceivedPacket::new);
}
