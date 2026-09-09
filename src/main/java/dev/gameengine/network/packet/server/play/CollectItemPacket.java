package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record CollectItemPacket(int collectedEntityId, int collectorEntityId, int pickupItemCount)
        implements ServerPacket.Play {
    public static final NetworkBuffer.Type<CollectItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, CollectItemPacket::collectedEntityId,
            VAR_INT, CollectItemPacket::collectorEntityId,
            VAR_INT, CollectItemPacket::pickupItemCount,
            CollectItemPacket::new);
}
