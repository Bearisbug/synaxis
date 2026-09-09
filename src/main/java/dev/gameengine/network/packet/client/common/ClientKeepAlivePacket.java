package dev.gameengine.network.packet.client.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.LONG;

public record ClientKeepAlivePacket(long id) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientKeepAlivePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, ClientKeepAlivePacket::id, ClientKeepAlivePacket::new);
}
