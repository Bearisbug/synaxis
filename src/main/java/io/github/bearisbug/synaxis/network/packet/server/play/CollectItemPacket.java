package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record CollectItemPacket(int collectedEntityId, int collectorEntityId, int pickupItemCount)
        implements ServerPacket.Play {
    public static final NetworkBuffer.Type<CollectItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, CollectItemPacket::collectedEntityId,
            VAR_INT, CollectItemPacket::collectorEntityId,
            VAR_INT, CollectItemPacket::pickupItemCount,
            CollectItemPacket::new);
}
