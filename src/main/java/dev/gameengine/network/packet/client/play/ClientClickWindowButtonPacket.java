package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientClickWindowButtonPacket(int windowId, int buttonId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientClickWindowButtonPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientClickWindowButtonPacket::windowId,
            VAR_INT, ClientClickWindowButtonPacket::buttonId,
            ClientClickWindowButtonPacket::new);
}
