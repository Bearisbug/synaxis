package io.github.bearisbug.synaxis.network.packet.client.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;

public record ClientKeepAlivePacket(long id) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientKeepAlivePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, ClientKeepAlivePacket::id, ClientKeepAlivePacket::new);
}
