package io.github.bearisbug.synaxis.network.packet.client.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;

public record ClientPingRequestPacket(long number) implements ClientPacket.Status, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPingRequestPacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, ClientPingRequestPacket::number, ClientPingRequestPacket::new);
}
