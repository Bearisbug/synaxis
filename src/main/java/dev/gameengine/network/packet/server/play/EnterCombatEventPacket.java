package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

public record EnterCombatEventPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EnterCombatEventPacket> SERIALIZER = NetworkBufferTemplate.template(new EnterCombatEventPacket());
}
