package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.SHORT;

public record ClientHeldItemChangePacket(short slot) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientHeldItemChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            SHORT, ClientHeldItemChangePacket::slot,
            ClientHeldItemChangePacket::new);
}
