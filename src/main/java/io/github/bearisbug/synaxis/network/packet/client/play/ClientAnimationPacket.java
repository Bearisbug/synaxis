package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientAnimationPacket(PlayerHand hand) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(PlayerHand.class), ClientAnimationPacket::hand,
            ClientAnimationPacket::new);
}
