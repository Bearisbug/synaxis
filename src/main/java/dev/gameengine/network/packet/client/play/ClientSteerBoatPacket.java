package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;

public record ClientSteerBoatPacket(boolean leftPaddleTurning, boolean rightPaddleTurning) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSteerBoatPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClientSteerBoatPacket::leftPaddleTurning,
            BOOLEAN, ClientSteerBoatPacket::rightPaddleTurning,
            ClientSteerBoatPacket::new);
}
