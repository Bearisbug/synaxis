package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record SetPassengersPacket(int vehicleEntityId,
                                  List<Integer> passengersId) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetPassengersPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, SetPassengersPacket::vehicleEntityId,
            VAR_INT.list(), SetPassengersPacket::passengersId,
            SetPassengersPacket::new);

    public SetPassengersPacket {
        passengersId = List.copyOf(passengersId);
    }
}
