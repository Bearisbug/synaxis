package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record TickStepPacket(int steps) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<TickStepPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, TickStepPacket::steps,
            TickStepPacket::new);
}
