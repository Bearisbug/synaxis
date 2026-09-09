package dev.gameengine.network.packet.client.play;

import dev.gameengine.entity.PlayerHand;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record ClientAnimationPacket(PlayerHand hand) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(PlayerHand.class), ClientAnimationPacket::hand,
            ClientAnimationPacket::new);
}
