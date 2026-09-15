package io.github.bearisbug.synaxis.network.packet.client.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record ClientPongPacket(int id) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPongPacket> SERIALIZER = NetworkBufferTemplate.template(
            INT, ClientPongPacket::id, ClientPongPacket::new);
}
