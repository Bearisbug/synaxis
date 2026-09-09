package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Vec;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.LP_VECTOR3;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientInteractEntityPacket(int targetId, PlayerHand hand, Vec location, boolean usingSecondaryAction) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientInteractEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientInteractEntityPacket::targetId,
            PlayerHand.NETWORK_TYPE, ClientInteractEntityPacket::hand,
            LP_VECTOR3, ClientInteractEntityPacket::location,
            BOOLEAN, ClientInteractEntityPacket::usingSecondaryAction,
            ClientInteractEntityPacket::new
    );
}
