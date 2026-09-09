package dev.gameengine.network.packet.server.play;

import dev.gameengine.entity.PlayerHand;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record OpenBookPacket(PlayerHand hand) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<OpenBookPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(PlayerHand.class), OpenBookPacket::hand,
            OpenBookPacket::new);
}
