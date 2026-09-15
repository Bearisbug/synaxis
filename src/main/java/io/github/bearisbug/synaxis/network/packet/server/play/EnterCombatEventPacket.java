package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public record EnterCombatEventPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EnterCombatEventPacket> SERIALIZER = NetworkBufferTemplate.template(new EnterCombatEventPacket());
}
