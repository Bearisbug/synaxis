package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record TickStepPacket(int steps) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<TickStepPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, TickStepPacket::steps,
            TickStepPacket::new);
}
