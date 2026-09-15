package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.POS;

public record ClientVehicleMovePacket(Pos position, boolean onGround) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientVehicleMovePacket> SERIALIZER = NetworkBufferTemplate.template(
            POS, ClientVehicleMovePacket::position,
            BOOLEAN, ClientVehicleMovePacket::onGround,
            ClientVehicleMovePacket::new);
}
