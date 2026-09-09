package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;

import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record ClientTeleportConfirmPacket(int teleportId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientTeleportConfirmPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientTeleportConfirmPacket::teleportId,
            ClientTeleportConfirmPacket::new);
}
