package dev.gameengine.network.packet.client.play;

import dev.gameengine.entity.PlayerHand;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientUseItemPacket(PlayerHand hand, int sequence, float yaw,
                                  float pitch) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientUseItemPacket> SERIALIZER = NetworkBufferTemplate.template(
            Enum(PlayerHand.class), ClientUseItemPacket::hand,
            VAR_INT, ClientUseItemPacket::sequence,
            FLOAT, ClientUseItemPacket::yaw,
            FLOAT, ClientUseItemPacket::pitch,
            ClientUseItemPacket::new);
}
