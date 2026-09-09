package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.POS;

public record ClientVehicleMovePacket(Pos position, boolean onGround) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientVehicleMovePacket> SERIALIZER = NetworkBufferTemplate.template(
            POS, ClientVehicleMovePacket::position,
            BOOLEAN, ClientVehicleMovePacket::onGround,
            ClientVehicleMovePacket::new);
}
