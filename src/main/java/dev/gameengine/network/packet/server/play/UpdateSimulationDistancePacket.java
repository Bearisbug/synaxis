package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record UpdateSimulationDistancePacket(int simulationDistance) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<UpdateSimulationDistancePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, UpdateSimulationDistancePacket::simulationDistance,
            UpdateSimulationDistancePacket::new);
}
