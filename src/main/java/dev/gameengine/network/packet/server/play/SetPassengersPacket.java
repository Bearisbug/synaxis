package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record SetPassengersPacket(int vehicleEntityId,
                                  List<Integer> passengersId) implements ServerPacket.Play {
    public static final int MAX_PASSENGERS = 16384;

    public static final NetworkBuffer.Type<SetPassengersPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SetPassengersPacket::vehicleEntityId,
            VAR_INT.list(MAX_PASSENGERS), SetPassengersPacket::passengersId,
            SetPassengersPacket::new);

    public SetPassengersPacket {
        passengersId = List.copyOf(passengersId);
    }
}
