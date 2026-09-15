package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientTeleportConfirmPacket(int teleportId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientTeleportConfirmPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientTeleportConfirmPacket::teleportId,
            ClientTeleportConfirmPacket::new);
}
