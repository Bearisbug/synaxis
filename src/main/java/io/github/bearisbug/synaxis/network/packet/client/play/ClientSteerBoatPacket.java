package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record ClientSteerBoatPacket(boolean leftPaddleTurning, boolean rightPaddleTurning) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSteerBoatPacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ClientSteerBoatPacket::leftPaddleTurning,
            BOOLEAN, ClientSteerBoatPacket::rightPaddleTurning,
            ClientSteerBoatPacket::new);
}
