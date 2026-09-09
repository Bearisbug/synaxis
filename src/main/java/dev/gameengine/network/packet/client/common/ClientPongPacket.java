package dev.gameengine.network.packet.client.common;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.INT;

public record ClientPongPacket(int id) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPongPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, ClientPongPacket::id, ClientPongPacket::new);
}
