package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LP_VECTOR3;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientInteractEntityPacket(int targetId, PlayerHand hand, Vec location, boolean usingSecondaryAction) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientInteractEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientInteractEntityPacket::targetId,
            PlayerHand.NETWORK_TYPE, ClientInteractEntityPacket::hand,
            LP_VECTOR3, ClientInteractEntityPacket::location,
            BOOLEAN, ClientInteractEntityPacket::usingSecondaryAction,
            ClientInteractEntityPacket::new
    );
}
