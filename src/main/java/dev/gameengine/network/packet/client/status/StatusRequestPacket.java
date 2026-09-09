package dev.gameengine.network.packet.client.status;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

public record StatusRequestPacket() implements ClientPacket.Status {
    public static final NetworkBuffer.Type<StatusRequestPacket> SERIALIZER = NetworkBufferTemplate.template(new StatusRequestPacket());
}
