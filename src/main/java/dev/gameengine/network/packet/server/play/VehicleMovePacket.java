package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import static dev.gameengine.network.NetworkBuffer.POS;

public record VehicleMovePacket(Pos position) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<VehicleMovePacket> SERIALIZER = NetworkBufferTemplate.template(
            POS, VehicleMovePacket::position, VehicleMovePacket::new);
}
