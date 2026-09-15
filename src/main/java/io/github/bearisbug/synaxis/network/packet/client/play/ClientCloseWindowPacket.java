package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientCloseWindowPacket(int windowId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCloseWindowPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientCloseWindowPacket::windowId,
            ClientCloseWindowPacket::new);
}
