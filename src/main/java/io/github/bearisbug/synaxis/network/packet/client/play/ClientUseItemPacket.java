package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientUseItemPacket(PlayerHand hand, int sequence, float yaw,
                                  float pitch) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientUseItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(PlayerHand.class), ClientUseItemPacket::hand,
            VAR_INT, ClientUseItemPacket::sequence,
            FLOAT, ClientUseItemPacket::yaw,
            FLOAT, ClientUseItemPacket::pitch,
            ClientUseItemPacket::new);
}
