package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record SetCooldownPacket(String cooldownGroup, int cooldownTicks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetCooldownPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, SetCooldownPacket::cooldownGroup,
            VAR_INT, SetCooldownPacket::cooldownTicks,
            SetCooldownPacket::new);
}
