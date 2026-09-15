package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record OpenBookPacket(PlayerHand hand) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<OpenBookPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(PlayerHand.class), OpenBookPacket::hand,
            OpenBookPacket::new);
}
