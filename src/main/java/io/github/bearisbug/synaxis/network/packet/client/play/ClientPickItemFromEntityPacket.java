package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientPickItemFromEntityPacket(int entityId, boolean includeData) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientPickItemFromEntityPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientPickItemFromEntityPacket::entityId,
            BOOLEAN, ClientPickItemFromEntityPacket::includeData,
            ClientPickItemFromEntityPacket::new);
}
