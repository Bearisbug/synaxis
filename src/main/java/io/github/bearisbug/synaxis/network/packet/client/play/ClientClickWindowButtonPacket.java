package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientClickWindowButtonPacket(int windowId, int buttonId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientClickWindowButtonPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientClickWindowButtonPacket::windowId,
            VAR_INT, ClientClickWindowButtonPacket::buttonId,
            ClientClickWindowButtonPacket::new);
}
