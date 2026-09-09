package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.SHORT;

public record ClientHeldItemChangePacket(short slot) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientHeldItemChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            SHORT, ClientHeldItemChangePacket::slot,
            ClientHeldItemChangePacket::new);
}
