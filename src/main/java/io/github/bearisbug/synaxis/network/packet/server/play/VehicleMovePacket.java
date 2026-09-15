package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.POS;

public record VehicleMovePacket(Pos position) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<VehicleMovePacket> SERIALIZER = NetworkBufferTemplate.template(
            POS, VehicleMovePacket::position, VehicleMovePacket::new);
}
