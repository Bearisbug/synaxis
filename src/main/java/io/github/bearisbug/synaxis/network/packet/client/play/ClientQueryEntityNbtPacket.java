package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientQueryEntityNbtPacket(int transactionId, int entityId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientQueryEntityNbtPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientQueryEntityNbtPacket::transactionId,
            VAR_INT, ClientQueryEntityNbtPacket::entityId,
            ClientQueryEntityNbtPacket::new);
}
