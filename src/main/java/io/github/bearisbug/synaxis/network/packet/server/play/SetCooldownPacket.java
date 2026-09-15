package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SetCooldownPacket(String cooldownGroup, int cooldownTicks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetCooldownPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, SetCooldownPacket::cooldownGroup,
            VAR_INT, SetCooldownPacket::cooldownTicks,
            SetCooldownPacket::new);
}
