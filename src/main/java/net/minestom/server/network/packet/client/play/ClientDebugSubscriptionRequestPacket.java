package net.minestom.server.network.packet.client.play;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.client.ClientPacket;

import java.util.Set;

/**
 * Debug subscription ids requested by the client (F3 screens). The engine does not
 * interpret them, the payload is kept only so the play state parse table stays complete.
 */
public record ClientDebugSubscriptionRequestPacket(
        Set<Integer> subscriptionIds
) implements ClientPacket.Play {
    /**
     * Number of debug subscriptions defined by protocol 776.
     */
    public static final int MAX_SUBSCRIPTIONS = 16;

    public static final NetworkBuffer.Type<ClientDebugSubscriptionRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT.set(MAX_SUBSCRIPTIONS), ClientDebugSubscriptionRequestPacket::subscriptionIds,
            ClientDebugSubscriptionRequestPacket::new);

    public ClientDebugSubscriptionRequestPacket {
        subscriptionIds = Set.copyOf(subscriptionIds);
    }
}
