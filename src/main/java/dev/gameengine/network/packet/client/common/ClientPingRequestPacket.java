package dev.gameengine.network.packet.client.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.LONG;

public record ClientPingRequestPacket(long number) implements ClientPacket.Status, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPingRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, ClientPingRequestPacket::number, ClientPingRequestPacket::new);
}
