package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record UpdateSimulationDistancePacket(int simulationDistance) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateSimulationDistancePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateSimulationDistancePacket::simulationDistance,
            UpdateSimulationDistancePacket::new);
}
