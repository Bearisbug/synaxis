package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record EndCombatEventPacket(int duration) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EndCombatEventPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EndCombatEventPacket::duration,
            EndCombatEventPacket::new);
}
